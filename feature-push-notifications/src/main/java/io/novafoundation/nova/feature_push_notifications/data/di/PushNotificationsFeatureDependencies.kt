package io.novafoundation.nova.feature_push_notifications.data.di

import android.content.Context
import coil.ImageLoader
import io.novafoundation.nova.common.data.storage.Preferences
import io.novafoundation.nova.common.resources.ResourceManager
import io.novafoundation.nova.common.utils.coroutines.RootScope
import io.novafoundation.nova.common.utils.permissions.PermissionsAskerFactory
import io.novafoundation.nova.feature_account_api.domain.interfaces.AccountRepository
import io.novafoundation.nova.feature_governance_api.data.source.GovernanceSourceRegistry
import io.novafoundation.nova.runtime.multiNetwork.ChainRegistry

interface PushNotificationsFeatureDependencies {

    val rootScope: RootScope

    val preferences: Preferences

    val context: Context

    val chainRegistry: ChainRegistry

    val permissionsAskerFactory: PermissionsAskerFactory

    val resourceManager: ResourceManager

    val accountRepository: AccountRepository

    val governanceSourceRegistry: GovernanceSourceRegistry

    val imageLoader: ImageLoader
}
