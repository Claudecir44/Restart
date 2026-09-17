package com.cjstudio.restart

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.net.URL

// Baixa o resultado que a Cloud Function devolveu (uma URL apontando pro
// arquivo gerado pela Replicate) — sem biblioteca externa, só
// java.net.URL/BitmapFactory (a mesma dependência mínima já usada no
// resto do app). Sempre chamar de uma coroutine em Dispatchers.IO.
object ImagemRedeUtil {
    fun baixarBitmap(url: String): Bitmap {
        URL(url).openStream().use { stream ->
            return BitmapFactory.decodeStream(stream)
                ?: throw IllegalStateException("Não foi possível decodificar a imagem recebida.")
        }
    }
}
