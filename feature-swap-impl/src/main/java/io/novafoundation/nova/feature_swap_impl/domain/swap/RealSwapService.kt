package io.novafoundation.nova.feature_swap_impl.domain.swap

import android.util.Log
import io.novafoundation.nova.common.data.memory.ComputationalCache
import io.novafoundation.nova.common.utils.MultiMap
import io.novafoundation.nova.common.utils.Percent
import io.novafoundation.nova.common.utils.accumulateMaps
import io.novafoundation.nova.common.utils.asPerbill
import io.novafoundation.nova.common.utils.atLeastZero
import io.novafoundation.nova.common.utils.filterNotNull
import io.novafoundation.nova.common.utils.flatMap
import io.novafoundation.nova.common.utils.flowOf
import io.novafoundation.nova.common.utils.isZero
import io.novafoundation.nova.common.utils.toPercent
import io.novafoundation.nova.feature_account_api.data.extrinsic.ExtrinsicSubmission
import io.novafoundation.nova.feature_swap_api.domain.model.SlippageConfig
import io.novafoundation.nova.feature_swap_api.domain.model.SwapDirection
import io.novafoundation.nova.feature_swap_api.domain.model.SwapExecuteArgs
import io.novafoundation.nova.feature_swap_api.domain.model.SwapFee
import io.novafoundation.nova.feature_swap_api.domain.model.SwapQuote
import io.novafoundation.nova.feature_swap_api.domain.model.SwapQuoteArgs
import io.novafoundation.nova.feature_swap_api.domain.swap.SwapService
import io.novafoundation.nova.feature_swap_impl.data.assetExchange.AssetExchange
import io.novafoundation.nova.feature_swap_impl.data.assetExchange.AssetExchangeQuote
import io.novafoundation.nova.feature_swap_impl.data.assetExchange.assetConversion.AssetConversionExchangeFactory
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.feature_wallet_api.domain.model.withAmount
import io.novafoundation.nova.runtime.ext.fullId
import io.novafoundation.nova.runtime.ext.isCommissionAsset
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.multiNetwork.chain.model.ChainId
import io.novafoundation.nova.runtime.multiNetwork.chain.model.FullChainAssetId
import io.novafoundation.nova.runtime.multiNetwork.findChains
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import kotlin.coroutines.coroutineContext

private const val ALL_DIRECTIONS_CACHE = "RealSwapService.ALL_DIRECTIONS"
private const val EXCHANGES_CACHE = "RealSwapService.EXCHANGES"

internal class RealSwapService(
    private val assetConversionFactory: AssetConversionExchangeFactory,
    private val computationalCache: ComputationalCache,
    private val chainRegistry: ChainRegistry
) : SwapService {

    override suspend fun canPayFeeInNonUtilityAsset(asset: Chain.Asset): Boolean = withContext(Dispatchers.Default) {
        val computationScope = CoroutineScope(coroutineContext)

        val exchange = exchanges(computationScope).getValue(asset.chainId)
        val isCustomFeeToken = !asset.isCommissionAsset

        isCustomFeeToken && exchange.canPayFeeInNonUtilityToken(asset)
    }

    override suspend fun assetsAvailableForSwap(
        computationScope: CoroutineScope
    ): Flow<Set<FullChainAssetId>> {
        return allAvailableDirections(computationScope).map { it.keys }
    }

    override suspend fun availableSwapDirectionsFor(
        asset: Chain.Asset,
        computationScope: CoroutineScope
    ): Flow<Set<FullChainAssetId>> {
        return allAvailableDirections(computationScope).map { it[asset.fullId].orEmpty() }
    }

    override suspend fun quote(args: SwapQuoteArgs): Result<SwapQuote> {
        val computationScope = CoroutineScope(coroutineContext)

        return runCatching {
            val exchange = exchanges(computationScope).getValue(args.tokenIn.configuration.chainId)
            val quote = exchange.quote(args)

            val (amountIn, amountOut) = args.inAndOutAmounts(quote)

            SwapQuote(
                amountIn = args.tokenIn.configuration.withAmount(amountIn),
                amountOut = args.tokenOut.configuration.withAmount(amountOut),
                direction = args.swapDirection,
                priceImpact = args.calculatePriceImpact(amountIn, amountOut),
            )
        }
    }

    override suspend fun estimateFee(args: SwapExecuteArgs): SwapFee {
        val computationScope = CoroutineScope(coroutineContext)
        val exchange = exchanges(computationScope).getValue(args.assetIn.chainId)

        val assetExchangeFee = exchange.estimateFee(args)

        return SwapFee(networkFee = assetExchangeFee.networkFee, minimumBalanceBuyIn = assetExchangeFee.minimumBalanceBuyIn)
    }

    override suspend fun swap(args: SwapExecuteArgs): Result<ExtrinsicSubmission> {
        val computationScope = CoroutineScope(coroutineContext)

        return runCatching { exchanges(computationScope).getValue(args.assetIn.chainId) }
            .flatMap { exchange -> exchange.swap(args) }
    }

    override suspend fun slippageConfig(chainId: ChainId): SlippageConfig? {
        val computationScope = CoroutineScope(coroutineContext)
        val exchanges = exchanges(computationScope)
        return exchanges[chainId]?.slippageConfig()
    }

    private fun SwapQuoteArgs.calculatePriceImpact(amountIn: Balance, amountOut: Balance): Percent {
        val fiatIn = tokenIn.planksToFiat(amountIn)
        val fiatOut = tokenOut.planksToFiat(amountOut)

        return calculatePriceImpact(fiatIn, fiatOut)
    }

    private fun SwapQuoteArgs.inAndOutAmounts(quote: AssetExchangeQuote): Pair<Balance, Balance> {
        return when (swapDirection) {
            SwapDirection.SPECIFIED_IN -> amount to quote.quote
            SwapDirection.SPECIFIED_OUT -> quote.quote to amount
        }
    }

    private fun calculatePriceImpact(fiatIn: BigDecimal, fiatOut: BigDecimal): Percent {
        if (fiatIn.isZero || fiatOut.isZero) return Percent.zero()

        val priceImpact = (BigDecimal.ONE - fiatOut / fiatIn).atLeastZero()

        return priceImpact.asPerbill().toPercent()
    }

    private suspend fun allAvailableDirections(computationScope: CoroutineScope): Flow<MultiMap<FullChainAssetId, FullChainAssetId>> {
        return computationalCache.useSharedFlow(ALL_DIRECTIONS_CACHE, computationScope) {
            val exchanges = exchanges(computationScope)

            val directionsByExchange = exchanges.map { (chainId, exchange) ->
                flowOf { exchange.availableSwapDirections() }
                    .catch {
                        emit(emptyMap())

                        Log.d("RealSwapService", "Failed to fetch directions for exchange ${exchange::class} in chain $chainId")
                    }
            }

            directionsByExchange
                .accumulateMaps()
                .filter { it.isNotEmpty() }
        }
    }

    private suspend fun exchanges(computationScope: CoroutineScope): Map<ChainId, AssetExchange> {
        return computationalCache.useCache(EXCHANGES_CACHE, computationScope) {
            createExchanges(computationScope)
        }
    }

    private suspend fun createExchanges(coroutineScope: CoroutineScope): Map<ChainId, AssetExchange> {
        return chainRegistry.findChains { it.swap.isNotEmpty() }
            .associateBy(Chain::id) { assetConversionFactory.create(it.id, coroutineScope) }
            .filterNotNull()
    }
}