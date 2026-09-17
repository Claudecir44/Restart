package com.cjstudio.restart

import android.graphics.Bitmap
import android.graphics.Color

// Recolore uma região (cabelo, olhos…) usando uma máscara vinda da IA na
// nuvem (ver IaNuvemRepository/OperacaoIa.SEGMENTAR), mas o recolorir em si
// é 100% local — troca só o matiz/saturação dos pixels na região marcada,
// preservando o brilho original (então sombra/luz/textura do cabelo
// continuam lá, só a cor muda). Sem isso, cada cor que o usuário
// experimentasse custaria outra chamada de IA generativa; assim só a
// SEGMENTAÇÃO usa IA (uma vez só), a cor é de graça e instantânea — dá até
// pra fazer um preview ao vivo com slider, se um dia quiser.
object RecolorirUtil {

    fun recolorirComMascara(original: Bitmap, mascara: Bitmap, corAlvo: Int): Bitmap {
        val mascaraRedimensionada = if (mascara.width != original.width || mascara.height != original.height) {
            Bitmap.createScaledBitmap(mascara, original.width, original.height, true)
        } else {
            mascara
        }

        val largura = original.width
        val altura = original.height
        val pixelsOriginais = IntArray(largura * altura)
        val pixelsMascara = IntArray(largura * altura)
        original.getPixels(pixelsOriginais, 0, largura, 0, 0, largura, altura)
        mascaraRedimensionada.getPixels(pixelsMascara, 0, largura, 0, 0, largura, altura)

        val hsvAlvo = FloatArray(3)
        Color.colorToHSV(corAlvo, hsvAlvo)

        val hsvPixel = FloatArray(3)
        val resultado = IntArray(largura * altura)

        for (i in pixelsOriginais.indices) {
            val peso = pesoDaMascara(pixelsMascara[i])
            if (peso <= 0.02f) {
                resultado[i] = pixelsOriginais[i]
                continue
            }

            val pixelOriginal = pixelsOriginais[i]
            Color.colorToHSV(pixelOriginal, hsvPixel)

            // Mantém o "value" (brilho/sombra) do pixel original — é isso
            // que preserva a textura/luz do cabelo/olho real. Só troca a
            // direção da cor (hue) e a saturação, e só na proporção do peso
            // da máscara (bordas ficam suaves, não um recorte serrilhado).
            val hFinal = misturarMatiz(hsvPixel[0], hsvAlvo[0], peso)
            val sFinal = lerp(hsvPixel[1], hsvAlvo[1], peso)
            val vFinal = hsvPixel[2]

            val corFinal = Color.HSVToColor(floatArrayOf(hFinal, sFinal, vFinal))
            val alphaOriginal = (pixelOriginal shr 24) and 0xFF
            resultado[i] = (alphaOriginal shl 24) or (corFinal and 0x00FFFFFF)
        }

        val bitmapResultado = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        bitmapResultado.setPixels(resultado, 0, largura, 0, 0, largura, altura)
        return bitmapResultado
    }

    // A máscara pode vir como recorte com alpha (transparente fora da
    // região) OU como imagem em escala de cinza (branco = região) —
    // cobre os dois formatos possíveis sem precisar saber qual a
    // Replicate devolveu.
    private fun pesoDaMascara(pixelMascara: Int): Float {
        val alpha = (pixelMascara shr 24) and 0xFF
        if (alpha < 255) return alpha / 255f

        val r = (pixelMascara shr 16) and 0xFF
        val g = (pixelMascara shr 8) and 0xFF
        val b = pixelMascara and 0xFF
        return ((r + g + b) / 3) / 255f
    }

    // Interpola ângulo (0-360°) pelo caminho mais curto — sem isso, ir de
    // 350° (vermelho) pra 10° (também vermelho, do outro lado do círculo)
    // passaria por 180° (ciano) no meio do caminho, errado.
    private fun misturarMatiz(de: Float, para: Float, peso: Float): Float {
        var diferenca = para - de
        if (diferenca > 180f) diferenca -= 360f
        if (diferenca < -180f) diferenca += 360f
        var resultado = de + diferenca * peso
        if (resultado < 0f) resultado += 360f
        if (resultado >= 360f) resultado -= 360f
        return resultado
    }

    private fun lerp(de: Float, para: Float, peso: Float): Float = de + (para - de) * peso
}
