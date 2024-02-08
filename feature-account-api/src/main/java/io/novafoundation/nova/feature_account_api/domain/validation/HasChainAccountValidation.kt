package io.novafoundation.nova.feature_account_api.domain.validation

import androidx.annotation.StringRes
import io.novafoundation.nova.common.mixin.api.CustomDialogDisplayer
import io.novafoundation.nova.common.mixin.api.CustomDialogDisplayer.Payload.DialogAction.Companion.noOp
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.validation.TransformedFailure
import io.novafoundation.nova.common.validation.Validation
import io.novafoundation.nova.common.validation.ValidationStatus
import io.novafoundation.nova.common.validation.ValidationSystemBuilder
import io.novafoundation.nova.common.validation.validationError
import io.novafoundation.nova.feature_account_api.R
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount.Type.LEDGER
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount.Type.PROXIED
import io.novafoundation.nova.feature_account_api.domain.model.MetaAccount
import io.novafoundation.nova.feature_account_api.domain.model.PolkadotVaultVariant
import io.novafoundation.nova.feature_account_api.domain.model.asPolkadotVaultVariantOrNull
import io.novafoundation.nova.feature_account_api.domain.model.hasAccountIn
import io.novafoundation.nova.feature_account_api.domain.validation.NoChainAccountFoundError.AddAccountState
import io.novafoundation.nova.feature_account_api.presenatation.account.polkadotVault.polkadotVaultLabelFor
import io.novafoundation.nova.feature_ledger_api.sdk.application.substrate.SubstrateApplicationConfig
import io.novafoundation.nova.feature_ledger_api.sdk.application.substrate.supports
import io.novafoundation.nova.runtime.multiNetwork.chain.model.Chain

interface NoChainAccountFoundError {
    val chain: Chain
    val account: MetaAccount
    val addAccountState: AddAccountState

    sealed class AddAccountState {
        object CanAdd : AddAccountState()

        object LedgerNotSupported : AddAccountState()

        class PolkadotVaultNotSupported(val variant: PolkadotVaultVariant) : AddAccountState()

        object ProxyAccountNotSupported : AddAccountState()
    }
}

class HasChainAccountValidation<P, E>(
    private val chainExtractor: (P) -> Chain,
    private val metaAccountExtractor: (P) -> MetaAccount,
    private val errorProducer: (chain: Chain, account: MetaAccount, addAccountState: AddAccountState) -> E
) : Validation<P, E> {

    override suspend fun validate(value: P): ValidationStatus<E> {
        val account = metaAccountExtractor(value)
        val chain = chainExtractor(value)
        val polkadotVaultVariant = account.type.asPolkadotVaultVariantOrNull()

        return when {
            account.hasAccountIn(chain) -> ValidationStatus.Valid()

            account.type == LEDGER && !SubstrateApplicationConfig.supports(chain.id) -> {
                errorProducer(chain, account, AddAccountState.LedgerNotSupported).validationError()
            }

            account.type == PROXIED -> {
                errorProducer(chain, account, AddAccountState.ProxyAccountNotSupported).validationError()
            }

            polkadotVaultVariant != null && chain.isEthereumBased -> {
                errorProducer(chain, account, AddAccountState.PolkadotVaultNotSupported(polkadotVaultVariant)).validationError()
            }

            else -> errorProducer(chain, account, AddAccountState.CanAdd).validationError()
        }
    }
}

fun <P, E> ValidationSystemBuilder<P, E>.hasChainAccount(
    chain: (P) -> Chain,
    metaAccount: (P) -> MetaAccount,
    error: (chain: Chain, account: MetaAccount, addAccountState: AddAccountState) -> E
) {
    validate(HasChainAccountValidation(chain, metaAccount, error))
}

fun handleChainAccountNotFound(
    failure: NoChainAccountFoundError,
    @StringRes addAccountDescriptionRes: Int,
    resourceManager: ResourceManager,
    goToWalletDetails: (metaAccountId: Long) -> Unit
): TransformedFailure {
    val chainName = failure.chain.name

    return when (val state = failure.addAccountState) {
        AddAccountState.CanAdd -> TransformedFailure.Custom(
            dialogPayload = CustomDialogDisplayer.Payload(
                title = resourceManager.getString(R.string.common_missing_account_title, chainName),
                message = resourceManager.getString(addAccountDescriptionRes, chainName),
                okAction = CustomDialogDisplayer.Payload.DialogAction(
                    title = resourceManager.getString(R.string.common_add),
                    action = { goToWalletDetails(failure.account.id) }
                ),
                cancelAction = noOp(resourceManager.getString(R.string.common_cancel)),
                customStyle = R.style.AccentNegativeAlertDialogTheme
            )
        )

        AddAccountState.LedgerNotSupported -> TransformedFailure.Default(
            resourceManager.getString(R.string.ledger_chain_not_supported, chainName) to null
        )

        is AddAccountState.PolkadotVaultNotSupported -> {
            val vaultLabel = resourceManager.polkadotVaultLabelFor(state.variant)

            TransformedFailure.Default(
                resourceManager.getString(R.string.account_parity_signer_chain_not_supported, vaultLabel, chainName) to null
            )
        }

        AddAccountState.ProxyAccountNotSupported -> TransformedFailure.Default(
            resourceManager.getString(R.string.common_network_not_supported, chainName) to null
        )
    }
}
