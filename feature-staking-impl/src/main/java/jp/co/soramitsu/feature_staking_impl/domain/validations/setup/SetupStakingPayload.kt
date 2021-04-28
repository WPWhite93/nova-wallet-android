package jp.co.soramitsu.feature_staking_impl.domain.validations.setup

import jp.co.soramitsu.feature_staking_impl.domain.model.StashSetup
import jp.co.soramitsu.feature_wallet_api.domain.model.Token
import java.math.BigDecimal

class SetupStakingPayload(
    val amount: BigDecimal,
    val tokenType: Token.Type,
    val maxFee: BigDecimal,
    val stashSetup: StashSetup
)