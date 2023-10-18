package io.novafoundation.nova.app.root.navigation.swap

import io.novafoundation.nova.app.R
import io.novafoundation.nova.app.root.navigation.BaseNavigator
import io.novafoundation.nova.app.root.navigation.NavigationHolder
import io.novafoundation.nova.feature_assets.presentation.AssetPayload
import io.novafoundation.nova.feature_assets.presentation.swap.AssetSwapFlowFragment
import io.novafoundation.nova.feature_assets.presentation.swap.SwapFlowPayload
import io.novafoundation.nova.feature_swap_impl.presentation.SwapRouter
import io.novafoundation.nova.runtime.multiNetwork.chain.model.FullChainAssetId

class SwapNavigator(
    private val navigationHolder: NavigationHolder
) : BaseNavigator(navigationHolder), SwapRouter {

    override fun openSwapConfirmation() {
        navigationHolder.navController?.navigate(R.id.action_swapMainSettingsFragment_to_swapConfirmationFragment)
    }

    override fun selectAssetIn(selectedAsset: FullChainAssetId?) {
        val selectedAssetModel = selectedAsset?.let { AssetPayload(it.chainId, it.assetId) }
        val payload = SwapFlowPayload(SwapFlowPayload.FlowType.SELECT_ASSET_IN, selectedAssetModel)
        navigationHolder.navController?.navigate(R.id.action_swapMainSettingsFragment_to_swapFlow, AssetSwapFlowFragment.getBundle(payload))
    }

    override fun selectAssetOut(selectedAsset: FullChainAssetId?) {
        val selectedAssetModel = selectedAsset?.let { AssetPayload(it.chainId, it.assetId) }
        val payload = SwapFlowPayload(SwapFlowPayload.FlowType.RESELECT_ASSET_OUT, selectedAssetModel)
        navigationHolder.navController?.navigate(R.id.action_swapMainSettingsFragment_to_swapFlow, AssetSwapFlowFragment.getBundle(payload))
    }
}
