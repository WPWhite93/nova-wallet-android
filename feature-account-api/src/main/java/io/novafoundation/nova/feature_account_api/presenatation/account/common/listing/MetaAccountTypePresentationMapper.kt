package io.novafoundation.nova.feature_account_api.presenatation.account.common.listing

import io.novafoundation.nova.common.view.ChipLabelModel
import io.novafoundation.nova.feature_account_api.domain.model.LightMetaAccount
import io.novafoundation.nova.feature_account_api.presenatation.account.listing.items.AccountChipGroupRvItem

interface MetaAccountTypePresentationMapper {
    fun mapMetaAccountTypeToUi(type: LightMetaAccount.Type): AccountChipGroupRvItem?

    fun mapTypeToChipLabel(type: LightMetaAccount.Type): ChipLabelModel?
}
