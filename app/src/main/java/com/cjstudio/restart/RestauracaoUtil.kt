package com.cjstudio.restart

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlin.math.max

// Restauração de foto 100% local (sem rede, sem Firebase) — primeira versão
// do app, só com processamento de imagem clássico (sem IA). Se o resultado
// for satisfatório, a ideia é depois trocar/complementar isso por um
// backend com modelo de IA de verdade (ver conversa que originou o projeto,
// Restart.docx), mantendo essa mesma tela como o modo "grátis"/offline.
object RestauracaoUtil {

    // Limite de resolução de trabalho — fotos de câmera/celular modernos
    // (12 a 100+ MP) processadas pixel a pixel em Kotlin puro (sem
    // biblioteca nativa tipo OpenCV) ficariam lentas demais e arriscam
    // OutOfMemoryError. 2048px no lado maior é suficiente pra tela de
    // celular e pra maioria dos usos de "restaurar uma foto antiga".
    private const val LADO_MAXIMO_TRABALHO = 2048

    // Lê a foto do Uri escolhido no Photo Picker, já orientada certo (EXIF)
    // e redimensionada pro limite de trabalho.
    fun carregarBitmap(context: Context, uri: Uri): Bitmap {
        val resolver = context.contentResolver

        val opcoesMedida = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opcoesMedida) }
        val larguraOriginal = opcoesMedida.outWidth
        val alturaOriginal = opcoesMedida.outHeight

        val maiorLado = max(larguraOriginal, alturaOriginal)
        var inSampleSize = 1
        while (maiorLado / inSampleSize > LADO_MAXIMO_TRABALHO * 2) {
            inSampleSize *= 2
        }

        val opcoesDecode = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        val bitmapBruto = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opcoesDecode)
        } ?: throw IllegalStateException("Não foi possível abrir a imagem selecionada.")

        val rotacao = lerRotacaoExif(context, uri)
        val bitmapOrientado = if (rotacao != 0) {
            val matriz = Matrix().apply { postRotate(rotacao.toFloat()) }
            Bitmap.createBitmap(bitmapBruto, 0, 0, bitmapBruto.width, bitmapBruto.height, matriz, true)
        } else {
            bitmapBruto
        }

        return redimensionarSeNecessario(bitmapOrientado, LADO_MAXIMO_TRABALHO)
    }

    private fun lerRotacaoExif(context: Context, uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        } catch (e: Exception) {
            // Nem toda imagem tem EXIF (ex.: PNG, ou já reprocessada antes) —
            // sem rotação é uma alternativa segura, não motivo pra falhar
            // a importação inteira.
            0
        }
    }

    private fun redimensionarSeNecessario(bitmap: Bitmap, ladoMaximo: Int): Bitmap {
        val maior = max(bitmap.width, bitmap.height)
        if (maior <= ladoMaximo) return bitmap
        val escala = ladoMaximo.toFloat() / maior
        val novaLargura = (bitmap.width * escala).toInt().coerceAtLeast(1)
        val novaAltura = (bitmap.height * escala).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, novaLargura, novaAltura, true)
    }

    // ===================== EFEITOS =====================

    // Nitidez via convolução (kernel clássico de "unsharp mask" 3x3).
    fun aplicarNitidez(bitmap: Bitmap): Bitmap {
        val kernel = floatArrayOf(
            0f, -1f, 0f,
            -1f, 5f, -1f,
            0f, -1f, 0f
        )
        return convolucao3x3(bitmap, kernel, divisor = 1f)
    }

    // Redução de ruído — aproximação de blur gaussiano 3x3 (mais suave que
    // um box blur simples, preserva um pouco mais de detalhe).
    fun aplicarReducaoRuido(bitmap: Bitmap): Bitmap {
        val kernel = floatArrayOf(
            1f, 2f, 1f,
            2f, 4f, 2f,
            1f, 2f, 1f
        )
        return convolucao3x3(bitmap, kernel, divisor = 16f)
    }

    // "Contraste automático" — estica o histograma de luminância entre os
    // percentis 1% e 99% (em vez de min/max cru, que um único pixel
    // queimado/preto puro distorceria) e aplica um leve reforço de
    // saturação, pra cores desbotadas ficarem mais vivas.
    fun aplicarContrasteAutomatico(bitmap: Bitmap): Bitmap {
        val largura = bitmap.width
        val altura = bitmap.height
        val pixels = IntArray(largura * altura)
        bitmap.getPixels(pixels, 0, largura, 0, 0, largura, altura)

        val histograma = IntArray(256)
        for (pixel in pixels) {
            val r = (pixel shr 16) and 0xFF
            val g = (pixel shr 8) and 0xFF
            val b = pixel and 0xFF
            val luminancia = (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
            histograma[luminancia]++
        }

        val total = pixels.size
        val cortePercentual = 0.01
        var acumulado = 0
        var low = 0
        for (i in 0..255) {
            acumulado += histograma[i]
            if (acumulado >= total * cortePercentual) {
                low = i
                break
            }
        }
        acumulado = 0
        var high = 255
        for (i in 255 downTo 0) {
            acumulado += histograma[i]
            if (acumulado >= total * cortePercentual) {
                high = i
                break
            }
        }
        if (high <= low) {
            high = 255
            low = 0
        }
        val faixa = (high - low).coerceAtLeast(1)

        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel shr 24) and 0xFF
            val r = esticarCanal((pixel shr 16) and 0xFF, low, faixa)
            val g = esticarCanal((pixel shr 8) and 0xFF, low, faixa)
            val b = esticarCanal(pixel and 0xFF, low, faixa)
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val resultado = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        resultado.setPixels(pixels, 0, largura, 0, 0, largura, altura)
        return reforcarSaturacao(resultado)
    }

    private fun esticarCanal(valor: Int, low: Int, faixa: Int): Int {
        return (((valor - low) * 255) / faixa).coerceIn(0, 255)
    }

    private fun reforcarSaturacao(bitmap: Bitmap): Bitmap {
        val resultado = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(resultado)
        val colorMatrix = ColorMatrix().apply { setSaturation(1.25f) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { colorFilter = ColorMatrixColorFilter(colorMatrix) }
        canvas.drawBitmap(bitmap, 0f, 0f, paint)
        return resultado
    }

    // Upscale simples (interpolação bilinear) — não "inventa" detalhe novo
    // como um super-resolution por IA faria, só deixa a imagem maior de
    // forma suave em vez de serrilhada.
    fun aumentarResolucao(bitmap: Bitmap, fator: Float = 2f): Bitmap {
        val novaLargura = (bitmap.width * fator).toInt()
        val novaAltura = (bitmap.height * fator).toInt()
        return Bitmap.createScaledBitmap(bitmap, novaLargura, novaAltura, true)
    }

    // "Restaurar (tudo)" — pipeline combinado. Ordem importa: reduz ruído
    // primeiro (senão a nitidez depois realça o próprio ruído), corrige
    // contraste/cor, e só por último aplica nitidez (fica mais "crocante"
    // sem realçar imperfeição que já devia ter sido suavizada).
    fun restaurarTudo(bitmap: Bitmap): Bitmap {
        val semRuido = aplicarReducaoRuido(bitmap)
        val comContraste = aplicarContrasteAutomatico(semRuido)
        return aplicarNitidez(comContraste)
    }

    // Convolução 3x3 genérica sobre um IntArray de pixels (muito mais
    // rápido que getPixel/setPixel pixel a pixel) — cada canal de cor é
    // processado separado, alpha é preservado sem alteração, bordas
    // simplesmente reusam o pixel mais próximo (clamp) em vez de warp
    // preto/transparente.
    private fun convolucao3x3(bitmap: Bitmap, kernel: FloatArray, divisor: Float): Bitmap {
        val largura = bitmap.width
        val altura = bitmap.height
        val origem = IntArray(largura * altura)
        bitmap.getPixels(origem, 0, largura, 0, 0, largura, altura)
        val destino = IntArray(largura * altura)

        for (y in 0 until altura) {
            for (x in 0 until largura) {
                var somaR = 0f
                var somaG = 0f
                var somaB = 0f
                var indiceKernel = 0
                for (dy in -1..1) {
                    val yVizinho = (y + dy).coerceIn(0, altura - 1)
                    for (dx in -1..1) {
                        val xVizinho = (x + dx).coerceIn(0, largura - 1)
                        val pixelVizinho = origem[yVizinho * largura + xVizinho]
                        val peso = kernel[indiceKernel]
                        somaR += ((pixelVizinho shr 16) and 0xFF) * peso
                        somaG += ((pixelVizinho shr 8) and 0xFF) * peso
                        somaB += (pixelVizinho and 0xFF) * peso
                        indiceKernel++
                    }
                }
                val pixelOriginal = origem[y * largura + x]
                val alpha = (pixelOriginal shr 24) and 0xFF
                val r = (somaR / divisor).toInt().coerceIn(0, 255)
                val g = (somaG / divisor).toInt().coerceIn(0, 255)
                val b = (somaB / divisor).toInt().coerceIn(0, 255)
                destino[y * largura + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        val resultado = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
        resultado.setPixels(destino, 0, largura, 0, 0, largura, altura)
        return resultado
    }
}
