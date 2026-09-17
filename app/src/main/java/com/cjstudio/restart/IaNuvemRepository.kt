package com.cjstudio.restart

import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await

// Espelha as chaves de REPLICATE_MODELOS em functions/index.js — mude os
// dois lados juntos.
enum class OperacaoIa(val chave: String) {
    RESTAURAR_ROSTO("restaurar_rosto"),
    COLORIZAR("colorizar"),
    AUMENTAR_RESOLUCAO("aumentar_resolucao"),
    REMOVER_FUNDO("remover_fundo"),
    SEGMENTAR("segmentar"),
    TROCAR_ROUPA("trocar_roupa"),
    ESTILIZAR("estilizar"),
}

sealed class ResultadoIa {
    data class Sucesso(val output: Any?) : ResultadoIa()
    data class Falha(val mensagem: String) : ResultadoIa()
}

// Chama iniciarProcessamento/verificarProcessamento (ver functions/
// index.js) — a Cloud Function só faz de proxy pra Replicate, quem
// realmente processa a imagem é o modelo de IA lá. Uma função suspend só
// já cuida do polling por dentro, quem chama não precisa saber que por
// trás são duas requisições.
class IaNuvemRepository {
    // "by lazy" (não no construtor) — enquanto o projeto Firebase do
    // Restart não existir de verdade (ver AndroidManifest.xml,
    // FirebaseInitProvider removido), FirebaseFunctions.getInstance() lança
    // IllegalStateException; se isso rodasse no construtor, quebrava a
    // MainActivity inteira (ela guarda um IaNuvemRepository como
    // propriedade) mesmo pra quem só quer usar os efeitos locais. Adiado
    // assim, o erro só aparece (e é tratado, ver processar()) quando o
    // usuário realmente toca num botão de IA.
    private val functions by lazy { FirebaseFunctions.getInstance() }

    suspend fun processar(
        operacao: OperacaoIa,
        imagemBase64: String,
        extras: Map<String, Any?> = emptyMap(),
        intervaloPollingMs: Long = 2500,
        tentativasMaximas: Int = 40 // ~100s no total — generativa pode demorar
    ): ResultadoIa {
        val dadosInicio = HashMap<String, Any?>(extras)
        dadosInicio["operacao"] = operacao.chave
        dadosInicio["imagemBase64"] = imagemBase64

        val predictionId = try {
            val resultado = functions.getHttpsCallable("iniciarProcessamento").call(dadosInicio).await()
            (resultado.data as? Map<*, *>)?.get("predictionId") as? String
                ?: return ResultadoIa.Falha("Resposta inesperada do servidor ao iniciar.")
        } catch (e: Exception) {
            return ResultadoIa.Falha(mensagemDeErro(e))
        }

        repeat(tentativasMaximas) {
            delay(intervaloPollingMs)
            try {
                val resultado = functions.getHttpsCallable("verificarProcessamento")
                    .call(mapOf("predictionId" to predictionId))
                    .await()
                val dados = resultado.data as? Map<*, *>
                    ?: return ResultadoIa.Falha("Resposta inesperada do servidor ao verificar.")
                when (dados["status"] as? String) {
                    "succeeded" -> return ResultadoIa.Sucesso(dados["output"])
                    "failed", "canceled" -> return ResultadoIa.Falha((dados["erro"] as? String) ?: "Processamento falhou.")
                    // "starting"/"processing" — continua tentando.
                }
            } catch (e: Exception) {
                return ResultadoIa.Falha(mensagemDeErro(e))
            }
        }
        return ResultadoIa.Falha("Tempo esgotado esperando o processamento.")
    }

    private fun mensagemDeErro(e: Exception): String {
        return if (e is FirebaseFunctionsException) e.message ?: e.code.name else e.message ?: "Erro desconhecido."
    }

    companion object {
        // Formatos de "output" variam por modelo na Replicate (string,
        // lista, ou objeto com uma url por chave) — pega a primeira URL de
        // imagem que achar, de um jeito que funciona pros três formatos.
        fun primeiraUrlDe(output: Any?): String? {
            return when (output) {
                is String -> output
                is List<*> -> output.firstOrNull() as? String
                is Map<*, *> -> output.values.firstOrNull() as? String
                else -> null
            }
        }
    }
}
