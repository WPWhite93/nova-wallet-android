@file:OptIn(ExperimentalCoroutinesApi::class)

package io.novafoundation.nova.feature_staking_impl.data.dashboard.network.updaters.chain

import io.novafoundation.nova.common.data.network.runtime.binding.bindNullableAccountId
import io.novafoundation.nova.common.utils.combineToPair
import io.novafoundation.nova.common.utils.staking
import io.novafoundation.nova.core.updater.GlobalScopeUpdater
import io.novafoundation.nova.core.updater.SharedRequestsBuilder
import io.novafoundation.nova.core.updater.Updater
import io.novafoundation.nova.core_db.model.StakingDashboardItemLocal
import io.novafoundation.nova.core_db.model.StakingDashboardItemLocal.Status
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.accountIdIn
import io.novafoundation.nova.feature_staking_api.domain.dashboard.model.StakingOptionId
import io.novafoundation.nova.feature_staking_api.domain.model.EraIndex
import io.novafoundation.nova.feature_staking_api.domain.model.Nominations
import io.novafoundation.nova.feature_staking_api.domain.model.StakingLedger
import io.novafoundation.nova.feature_staking_impl.data.dashboard.cache.StakingDashboardCache
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.ChainStakingStats
import io.novafoundation.nova.feature_staking_impl.data.dashboard.network.stats.MultiChainStakingStats
import io.novafoundation.nova.feature_staking_impl.data.network.blockhain.bindings.bindActiveEra
import io.novafoundation.nova.feature_staking_impl.data.network.blockhain.bindings.bindNominationsOrNull
import io.novafoundation.nova.feature_staking_impl.data.network.blockhain.bindings.bindStakingLedgerOrNull
import io.novafoundation.nova.feature_staking_impl.domain.common.isWaiting
import io.novafoundation.nova.feature_wallet_api.data.network.blockhain.types.Balance
import io.novafoundation.nova.runtime.multiNetwork.chain.mappers.mapStakingTypeToStakingString
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain
import io.novafoundation.nova.runtime.storage.source.StorageDataSource
import io.novafoundation.nova.runtime.storage.source.query.metadata
import jp.co.soramitsu.fearless_utils.runtime.AccountId
import jp.co.soramitsu.fearless_utils.runtime.metadata.storage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformLatest

class StakingDashboardRelayStakingUpdater(
    private val chain: Chain,
    private val chainAsset: Chain.Asset,
    private val stakingType: Chain.Asset.StakingType,
    private val metaAccount: MetaAccount,
    private val stakingStatsAsync: Deferred<MultiChainStakingStats>,
    private val stakingDashboardCache: StakingDashboardCache,
    private val remoteStorageSource: StorageDataSource
) : GlobalScopeUpdater {

    private val stakingTypeLocal = requireNotNull(mapStakingTypeToStakingString(stakingType))

    override val requiredModules: List<String> = emptyList()

    override suspend fun listenForUpdates(storageSubscriptionBuilder: SharedRequestsBuilder): Flow<Updater.SideEffect> {
        val accountId = metaAccount.accountIdIn(chain) ?: return emptyFlow() // TODO handle no accounts present

        return remoteStorageSource.subscribe(chain.id, storageSubscriptionBuilder) {
            val activeEraFlow = metadata.staking().storage("ActiveEra").observe(binding = ::bindActiveEra)
            val bondedFlow = metadata.staking().storage("Bonded").observe(accountId, binding = ::bindNullableAccountId)

            val baseInfo = bondedFlow.flatMapLatest { maybeController ->
                val controllerId = maybeController ?: accountId

                subscribeToStakingState(controllerId)
            }

            combineToPair(baseInfo, activeEraFlow)
        }.transformLatest { (relaychainStakingState, activeEra) ->
            if (stakingStatsAsync.isActive) {
                // we only save base state if secondary is still loading, to avoid unnecessary writes to db
                saveItem(relaychainStakingState, secondaryInfo = null)
            }

            val stakingStats = stakingStatsAsync.await()
            val secondaryInfo = constructSecondaryInfo(relaychainStakingState, activeEra, stakingStats)

            saveItem(relaychainStakingState, secondaryInfo)

            emit(StakingDashboardOptionUpdated(stakingOptionId()))
        }
    }

    private fun subscribeToStakingState(controllerId: AccountId): Flow<RelaychainStakingBaseInfo?> {
        return remoteStorageSource.subscribe(chain.id) {
            metadata.staking().storage("Ledger").observe(controllerId, binding = ::bindStakingLedgerOrNull).flatMapLatest { ledger ->
                if (ledger != null) {
                    subscribeToNominations(ledger.stashId).map { nominations ->
                        RelaychainStakingBaseInfo(ledger, nominations)
                    }
                } else {
                    flowOf(null)
                }
            }
        }
    }

    private suspend fun subscribeToNominations(stashId: AccountId): Flow<Nominations?> {
        return remoteStorageSource.subscribe(chain.id) {
            metadata.staking().storage("Nominators").observe(stashId, binding = ::bindNominationsOrNull)
        }
    }

    private suspend fun saveItem(
        relaychainStakingBaseInfo: RelaychainStakingBaseInfo?,
        secondaryInfo: RelaychainStakingSecondaryInfo?
    ) = stakingDashboardCache.update { fromCache ->
        if (relaychainStakingBaseInfo != null) {
            StakingDashboardItemLocal.staking(
                chainId = chain.id,
                chainAssetId = chainAsset.id,
                stakingType = stakingTypeLocal,
                metaId = metaAccount.id,
                stake = relaychainStakingBaseInfo.stakingLedger.active,
                status = secondaryInfo?.status ?: fromCache?.status,
                rewards = secondaryInfo?.rewards ?: fromCache?.rewards,
                estimatedEarnings = secondaryInfo?.estimatedEarnings ?: fromCache?.estimatedEarnings
            )
        } else {
            StakingDashboardItemLocal.notStaking(
                chainId = chain.id,
                chainAssetId = chainAsset.id,
                stakingType = stakingTypeLocal,
                metaId = metaAccount.id,
                estimatedEarnings = secondaryInfo?.estimatedEarnings ?: fromCache?.estimatedEarnings
            )
        }
    }

    private suspend fun StakingDashboardCache.update(updating: (StakingDashboardItemLocal?) -> StakingDashboardItemLocal) {
        update(chain.id, chainAsset.id, stakingTypeLocal, metaAccount.id, updating)
    }

    private fun constructSecondaryInfo(
        baseInfo: RelaychainStakingBaseInfo?,
        activeEra: EraIndex,
        multiChainStakingStats: MultiChainStakingStats,
    ): RelaychainStakingSecondaryInfo? {
        val optionId = StakingOptionId(chain.id, chainAsset.id, stakingType)
        val chainStakingStats = multiChainStakingStats[optionId] ?: return null

        return RelaychainStakingSecondaryInfo(
            rewards = chainStakingStats.rewards,
            estimatedEarnings = chainStakingStats.estimatedEarnings.value,
            status = determineStakingStatus(baseInfo, activeEra, chainStakingStats)
        )
    }

    private fun determineStakingStatus(
        baseInfo: RelaychainStakingBaseInfo?,
        activeEra: EraIndex,
        chainStakingStats: ChainStakingStats,
    ): Status? {
        return when {
            baseInfo == null -> null
            chainStakingStats.accountPresentInActiveStakers -> Status.ACTIVE
            baseInfo.nominations != null && baseInfo.nominations.isWaiting(activeEra) -> Status.WAITING
            else -> Status.INACTIVE
        }
    }

    private fun stakingOptionId(): StakingOptionId {
        return StakingOptionId(chain.id, chainAsset.id, stakingType)
    }
}

private class RelaychainStakingBaseInfo(
    val stakingLedger: StakingLedger,
    val nominations: Nominations?,
)

private class RelaychainStakingSecondaryInfo(
    val rewards: Balance,
    val estimatedEarnings: Double,
    val status: Status?
)
