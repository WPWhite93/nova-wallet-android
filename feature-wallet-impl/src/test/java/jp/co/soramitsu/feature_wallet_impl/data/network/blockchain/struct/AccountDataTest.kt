package jp.co.soramitsu.feature_wallet_impl.data.network.blockchain.struct

import io.emeraldpay.polkaj.scale.ScaleCodecReader
import io.emeraldpay.polkaj.scale.ScaleCodecWriter
import jp.co.soramitsu.feature_wallet_impl.data.network.blockchain.struct.AccountData.free
import jp.co.soramitsu.feature_wallet_impl.data.network.blockchain.struct.AccountInfo.data
import jp.co.soramitsu.feature_wallet_impl.data.network.blockchain.struct.AccountInfo.nonce
import junit.framework.Assert.assertEquals
import org.bouncycastle.util.encoders.Hex
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner
import java.io.ByteArrayOutputStream

@RunWith(MockitoJUnitRunner::class)
class AccountDataTest {
    @Test
    fun `should decode and encode account info`() {
        val response = "1a00000000ffebb7648e0600000000000000000000f81f4aa9d101000000000000000000000000000000000000000000000000000000000000000000000000000000000000"
        val actualBalance = 7208644897791.toBigInteger()
        val actualRefCount = 26.toUInt()

        val decode = Hex.decode(response)

        val reader = ScaleCodecReader(decode)

        val struct = AccountInfo.read(reader)

        val balanceInPlanks = struct[data][free]

        assert(balanceInPlanks == actualBalance)
        assert(struct[nonce] == actualRefCount)

        val outputStream = ByteArrayOutputStream()
        val writer = ScaleCodecWriter(outputStream)
        writer.write(AccountInfo, struct)
        val bytes = outputStream.toByteArray()

        val encodedResponse = Hex.toHexString(bytes)

        assertEquals(response, encodedResponse)
    }
}