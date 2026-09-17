package com.cjstudio.restart

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Tela única do app — importar uma foto do celular (Photo Picker, sem
// precisar de permissão) e restaurar/editar de dois jeitos:
// - Efeitos locais (RestauracaoUtil) — sem internet, sem custo, instantâneo.
// - Efeitos com IA na nuvem (IaNuvemRepository -> Cloud Functions ->
//   Replicate, ver functions/index.js) — estilização, remover/trocar
//   fundo, cor de cabelo/olhos, trocar roupa: coisas que processamento de
//   imagem clássico não consegue fazer.
class MainActivity : AppCompatActivity() {

    private lateinit var btnEscolherFoto: Button
    private lateinit var containerFoto: View
    private lateinit var ivAntes: ImageView
    private lateinit var ivResultado: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvProcessando: TextView
    private lateinit var botoesEfeito: List<Button>
    private lateinit var btnSalvar: Button
    private lateinit var btnCompartilhar: Button

    private lateinit var progressBarIa: ProgressBar
    private lateinit var tvProcessandoIa: TextView
    private lateinit var botoesIa: List<Button>

    private var bitmapOriginal: Bitmap? = null
    private var bitmapAtual: Bitmap? = null
    private var bitmapRoupaReferencia: Bitmap? = null

    private val iaNuvemRepository = IaNuvemRepository()

    private val selecionarFoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) carregarFoto(uri)
    }

    // Segundo picker, só pra "Trocar roupa" (ver escolherRoupaEProcessar) —
    // a foto da peça de roupa desejada, separada da foto principal.
    private val selecionarRoupa = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) continuarTrocaDeRoupa(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEscolherFoto = findViewById(R.id.btnEscolherFoto)
        containerFoto = findViewById(R.id.containerFoto)
        ivAntes = findViewById(R.id.ivAntes)
        ivResultado = findViewById(R.id.ivResultado)
        progressBar = findViewById(R.id.progressBar)
        tvProcessando = findViewById(R.id.tvProcessando)
        btnSalvar = findViewById(R.id.btnSalvar)
        btnCompartilhar = findViewById(R.id.btnCompartilhar)
        progressBarIa = findViewById(R.id.progressBarIa)
        tvProcessandoIa = findViewById(R.id.tvProcessandoIa)

        val btnRestaurar = findViewById<Button>(R.id.btnRestaurar)
        val btnNitidez = findViewById<Button>(R.id.btnNitidez)
        val btnReduzirRuido = findViewById<Button>(R.id.btnReduzirRuido)
        val btnContrasteAuto = findViewById<Button>(R.id.btnContrasteAuto)
        val btnAumentarResolucao = findViewById<Button>(R.id.btnAumentarResolucao)
        val btnDesfazer = findViewById<Button>(R.id.btnDesfazer)
        botoesEfeito = listOf(btnRestaurar, btnNitidez, btnReduzirRuido, btnContrasteAuto, btnAumentarResolucao, btnDesfazer)

        val btnIaRestaurarRosto = findViewById<Button>(R.id.btnIaRestaurarRosto)
        val btnIaColorir = findViewById<Button>(R.id.btnIaColorir)
        val btnIaAumentarResolucao = findViewById<Button>(R.id.btnIaAumentarResolucao)
        val btnIaRemoverFundo = findViewById<Button>(R.id.btnIaRemoverFundo)
        val btnIaEstilizar = findViewById<Button>(R.id.btnIaEstilizar)
        val btnIaCorCabelo = findViewById<Button>(R.id.btnIaCorCabelo)
        val btnIaCorOlhos = findViewById<Button>(R.id.btnIaCorOlhos)
        val btnIaTrocarRoupa = findViewById<Button>(R.id.btnIaTrocarRoupa)
        botoesIa = listOf(
            btnIaRestaurarRosto, btnIaColorir, btnIaAumentarResolucao, btnIaRemoverFundo,
            btnIaEstilizar, btnIaCorCabelo, btnIaCorOlhos, btnIaTrocarRoupa
        )

        btnEscolherFoto.setOnClickListener {
            selecionarFoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }

        btnRestaurar.setOnClickListener { aplicarEfeitoLocal(RestauracaoUtil::restaurarTudo) }
        btnNitidez.setOnClickListener { aplicarEfeitoLocal(RestauracaoUtil::aplicarNitidez) }
        btnReduzirRuido.setOnClickListener { aplicarEfeitoLocal(RestauracaoUtil::aplicarReducaoRuido) }
        btnContrasteAuto.setOnClickListener { aplicarEfeitoLocal(RestauracaoUtil::aplicarContrasteAutomatico) }
        btnAumentarResolucao.setOnClickListener { aplicarEfeitoLocal { bitmap -> RestauracaoUtil.aumentarResolucao(bitmap) } }
        btnDesfazer.setOnClickListener { desfazer() }

        btnIaRestaurarRosto.setOnClickListener { executarOperacaoIaSimples(OperacaoIa.RESTAURAR_ROSTO) }
        btnIaColorir.setOnClickListener { executarOperacaoIaSimples(OperacaoIa.COLORIZAR) }
        btnIaAumentarResolucao.setOnClickListener { executarOperacaoIaSimples(OperacaoIa.AUMENTAR_RESOLUCAO) }
        btnIaRemoverFundo.setOnClickListener { executarOperacaoIaSimples(OperacaoIa.REMOVER_FUNDO) }
        btnIaEstilizar.setOnClickListener { mostrarDialogEstilo() }
        btnIaCorCabelo.setOnClickListener { mostrarDialogCor(getString(R.string.ia_escolha_cor_titulo), R.array.ia_cores_cabelo_nomes, CORES_CABELO, REGIAO_CABELO) }
        btnIaCorOlhos.setOnClickListener { mostrarDialogCor(getString(R.string.ia_escolha_cor_titulo), R.array.ia_cores_olhos_nomes, CORES_OLHOS, REGIAO_OLHOS) }
        btnIaTrocarRoupa.setOnClickListener { iniciarTrocaDeRoupa() }

        btnSalvar.setOnClickListener { salvarFoto() }
        btnCompartilhar.setOnClickListener { compartilharFoto() }
    }

    private fun carregarFoto(uri: Uri) {
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                val bitmap = withContext(Dispatchers.IO) { RestauracaoUtil.carregarBitmap(this@MainActivity, uri) }
                bitmapOriginal = bitmap
                bitmapAtual = bitmap
                ivAntes.setImageBitmap(bitmap)
                ivResultado.setImageBitmap(bitmap)
                containerFoto.visibility = View.VISIBLE
                btnEscolherFoto.text = getString(R.string.botao_trocar_foto)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_erro_processar, e.message), Toast.LENGTH_LONG).show()
            } finally {
                definirCarregando(false)
            }
        }
    }

    // ===================== EFEITOS LOCAIS =====================

    // Cada efeito parte do resultado ATUAL (não sempre do original) — dá
    // pra combinar vários em sequência (ex.: Reduzir ruído, depois
    // Nitidez). "Desfazer" é o único jeito de voltar pro original.
    private fun aplicarEfeitoLocal(transformar: (Bitmap) -> Bitmap) {
        val atual = bitmapAtual
        if (atual == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                val resultado = withContext(Dispatchers.Default) { transformar(atual) }
                atualizarResultado(resultado)
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_erro_processar, e.message), Toast.LENGTH_LONG).show()
            } finally {
                definirCarregando(false)
            }
        }
    }

    private fun desfazer() {
        val original = bitmapOriginal ?: return
        atualizarResultado(original)
    }

    private fun atualizarResultado(bitmap: Bitmap) {
        bitmapAtual = bitmap
        ivResultado.setImageBitmap(bitmap)
    }

    private fun definirCarregando(carregando: Boolean) {
        progressBar.visibility = if (carregando) View.VISIBLE else View.GONE
        tvProcessando.visibility = if (carregando) View.VISIBLE else View.GONE
        progressBarIa.visibility = if (carregando) View.VISIBLE else View.GONE
        tvProcessandoIa.visibility = if (carregando) View.VISIBLE else View.GONE
        btnEscolherFoto.isEnabled = !carregando
        btnSalvar.isEnabled = !carregando
        btnCompartilhar.isEnabled = !carregando
        botoesEfeito.forEach { it.isEnabled = !carregando }
        botoesIa.forEach { it.isEnabled = !carregando }
    }

    // ===================== EFEITOS COM IA NA NUVEM =====================
    // Ver IaNuvemRepository.kt / functions/index.js — cada botão manda a
    // foto atual (bitmapAtual, a mesma sobre a qual os efeitos locais
    // atuam) em base64 pra uma Cloud Function, que chama um modelo na
    // Replicate e devolve o resultado.

    private fun bitmapParaBase64(bitmap: Bitmap): String {
        val saida = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, saida)
        val base64 = Base64.encodeToString(saida.toByteArray(), Base64.NO_WRAP)
        return "data:image/jpeg;base64,$base64"
    }

    // Restaurar rosto / Colorir / Aumentar resolução (IA) / Remover fundo —
    // todos "uma imagem entra, uma imagem sai", só muda a operação.
    private fun executarOperacaoIaSimples(operacao: OperacaoIa, extras: Map<String, Any?> = emptyMap()) {
        val atual = bitmapAtual
        if (atual == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.Default) { bitmapParaBase64(atual) }
                when (val resultado = iaNuvemRepository.processar(operacao, base64, extras)) {
                    is ResultadoIa.Sucesso -> {
                        val url = IaNuvemRepository.primeiraUrlDe(resultado.output)
                        if (url == null) {
                            mostrarErroIa("Resposta sem imagem de resultado.")
                            return@launch
                        }
                        val bitmapResultado = withContext(Dispatchers.IO) { ImagemRedeUtil.baixarBitmap(url) }
                        atualizarResultado(bitmapResultado)
                    }
                    is ResultadoIa.Falha -> mostrarErroIa(resultado.mensagem)
                }
            } catch (e: Exception) {
                mostrarErroIa(e.message ?: "Erro desconhecido.")
            } finally {
                definirCarregando(false)
            }
        }
    }

    private fun mostrarErroIa(mensagem: String) {
        Toast.makeText(this, getString(R.string.ia_erro_formato, mensagem), Toast.LENGTH_LONG).show()
    }

    // ----- Estilizar -----

    private fun mostrarDialogEstilo() {
        if (bitmapAtual == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        val nomes = resources.getStringArray(R.array.ia_estilos_nomes)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ia_escolha_estilo_titulo)
            .setItems(nomes) { _, posicao ->
                val chave = CHAVES_ESTILO[posicao]
                executarOperacaoIaSimples(OperacaoIa.ESTILIZAR, mapOf("estilo" to chave))
            }
            .setNegativeButton(R.string.cancelar, null)
            .show()
    }

    // ----- Cor do cabelo / Cor dos olhos -----
    // Só a SEGMENTAÇÃO usa IA (uma chamada) — a recoloração em si é local
    // (ver RecolorirUtil), então trocar de cor de novo depois não custa
    // outra chamada de IA, é instantâneo.

    private fun mostrarDialogCor(titulo: String, arrayNomes: Int, cores: IntArray, regiao: String) {
        if (bitmapAtual == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        val nomes = resources.getStringArray(arrayNomes)
        MaterialAlertDialogBuilder(this)
            .setTitle(titulo)
            .setItems(nomes) { _, posicao -> aplicarRecolorComMascara(regiao, cores[posicao]) }
            .show()
    }

    private fun aplicarRecolorComMascara(regiao: String, corAlvo: Int) {
        val atual = bitmapAtual ?: return
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                val base64 = withContext(Dispatchers.Default) { bitmapParaBase64(atual) }
                when (val resultado = iaNuvemRepository.processar(OperacaoIa.SEGMENTAR, base64)) {
                    is ResultadoIa.Sucesso -> {
                        val urlMascara = extrairUrlDaRegiao(resultado.output, regiao)
                        if (urlMascara == null) {
                            Toast.makeText(this@MainActivity, R.string.ia_sem_mascara_para_regiao, Toast.LENGTH_LONG).show()
                            return@launch
                        }
                        val mascara = withContext(Dispatchers.IO) { ImagemRedeUtil.baixarBitmap(urlMascara) }
                        val recolorido = withContext(Dispatchers.Default) {
                            RecolorirUtil.recolorirComMascara(atual, mascara, corAlvo)
                        }
                        atualizarResultado(recolorido)
                    }
                    is ResultadoIa.Falha -> mostrarErroIa(resultado.mensagem)
                }
            } catch (e: Exception) {
                mostrarErroIa(e.message ?: "Erro desconhecido.")
            } finally {
                definirCarregando(false)
            }
        }
    }

    // O formato exato de "output" do modelo de segmentação (ver
    // REPLICATE_MODELOS.segmentar em functions/index.js) precisa ser
    // confirmado contra a API real antes do primeiro teste de verdade —
    // aqui tentamos os nomes de chave mais prováveis pra cada região.
    private fun extrairUrlDaRegiao(output: Any?, regiao: String): String? {
        val mapa = output as? Map<*, *> ?: return null
        val chavesPossiveis = when (regiao) {
            REGIAO_CABELO -> listOf("hair", "cabelo")
            REGIAO_OLHOS -> listOf("eyes", "eye", "left_eye", "olhos")
            else -> listOf(regiao)
        }
        for (chave in chavesPossiveis) {
            val valor = mapa[chave]
            if (valor is String) return valor
        }
        return null
    }

    // ----- Trocar roupa -----
    // Precisa de uma SEGUNDA foto (a peça de roupa desejada) + categoria —
    // ver IDM-VTON em REPLICATE_MODELOS.trocar_roupa.

    private fun iniciarTrocaDeRoupa() {
        if (bitmapAtual == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, R.string.ia_escolha_roupa_referencia, Toast.LENGTH_LONG).show()
        selecionarRoupa.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    private fun continuarTrocaDeRoupa(uriRoupa: Uri) {
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                bitmapRoupaReferencia = withContext(Dispatchers.IO) { RestauracaoUtil.carregarBitmap(this@MainActivity, uriRoupa) }
                mostrarDialogCategoriaRoupa()
            } catch (e: Exception) {
                mostrarErroIa(e.message ?: "Erro ao carregar a foto da roupa.")
            } finally {
                definirCarregando(false)
            }
        }
    }

    private fun mostrarDialogCategoriaRoupa() {
        val nomes = resources.getStringArray(R.array.ia_categorias_roupa_nomes)
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.ia_escolha_categoria_titulo)
            .setItems(nomes) { _, posicao ->
                val categoria = CATEGORIAS_ROUPA[posicao]
                executarTrocaDeRoupa(categoria)
            }
            .show()
    }

    private fun executarTrocaDeRoupa(categoria: String) {
        val atual = bitmapAtual ?: return
        val roupa = bitmapRoupaReferencia ?: return
        definirCarregando(true)
        lifecycleScope.launch {
            try {
                val basePessoa = withContext(Dispatchers.Default) { bitmapParaBase64(atual) }
                val baseRoupa = withContext(Dispatchers.Default) { bitmapParaBase64(roupa) }
                val extras = mapOf("roupaBase64" to baseRoupa, "categoria" to categoria)
                when (val resultado = iaNuvemRepository.processar(OperacaoIa.TROCAR_ROUPA, basePessoa, extras)) {
                    is ResultadoIa.Sucesso -> {
                        val url = IaNuvemRepository.primeiraUrlDe(resultado.output)
                        if (url == null) {
                            mostrarErroIa("Resposta sem imagem de resultado.")
                            return@launch
                        }
                        val bitmapResultado = withContext(Dispatchers.IO) { ImagemRedeUtil.baixarBitmap(url) }
                        atualizarResultado(bitmapResultado)
                    }
                    is ResultadoIa.Falha -> mostrarErroIa(resultado.mensagem)
                }
            } catch (e: Exception) {
                mostrarErroIa(e.message ?: "Erro desconhecido.")
            } finally {
                definirCarregando(false)
            }
        }
    }

    // ===================== SALVAR / COMPARTILHAR =====================

    // Salva em Pictures/Restart via MediaStore — funciona em qualquer
    // versão do Android sem pedir permissão a partir do 10 (API 29,
    // armazenamento com escopo); em versões antigas usa a permissão
    // declarada no manifesto (maxSdkVersion 28), concedida na instalação.
    private fun salvarFoto() {
        val bitmap = bitmapAtual
        if (bitmap == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { salvarNoMediaStore(bitmap) }
                Toast.makeText(this@MainActivity, R.string.toast_salvo_sucesso, Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_erro_salvar, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun salvarNoMediaStore(bitmap: Bitmap) {
        val nomeArquivo = "restart_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.jpg"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, nomeArquivo)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Restart")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }

        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("Não foi possível criar o arquivo de imagem.")

        resolver.openOutputStream(uri)?.use { saida ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, saida)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
    }

    // Compartilhar não depende de já ter salvo — grava uma cópia temporária
    // no cache (ver res/xml/file_paths.xml) só pra gerar o Uri via
    // FileProvider que outros apps conseguem ler.
    private fun compartilharFoto() {
        val bitmap = bitmapAtual
        if (bitmap == null) {
            Toast.makeText(this, R.string.toast_selecione_foto_primeiro, Toast.LENGTH_SHORT).show()
            return
        }
        lifecycleScope.launch {
            try {
                val uri = withContext(Dispatchers.IO) { salvarNoCacheParaCompartilhar(bitmap) }
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(intent, getString(R.string.botao_compartilhar)))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, getString(R.string.toast_erro_salvar, e.message), Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun salvarNoCacheParaCompartilhar(bitmap: Bitmap): Uri {
        val pasta = File(cacheDir, "compartilhadas").apply { mkdirs() }
        val arquivo = File(pasta, "restart_${System.currentTimeMillis()}.jpg")
        FileOutputStream(arquivo).use { saida -> bitmap.compress(Bitmap.CompressFormat.JPEG, 95, saida) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", arquivo)
    }

    companion object {
        private const val REGIAO_CABELO = "cabelo"
        private const val REGIAO_OLHOS = "olhos"

        // Mesma ordem de R.array.ia_estilos_nomes — índice do item tocado
        // vira a chave de PROMPTS_ESTILO em functions/index.js.
        private val CHAVES_ESTILO = listOf("pintura", "retrato", "desenho", "lapis", "vintage")

        // Mesma ordem de R.array.ia_categorias_roupa_nomes.
        private val CATEGORIAS_ROUPA = listOf("upper_body", "lower_body", "dresses")

        // Mesma ordem de R.array.ia_cores_cabelo_nomes.
        private val CORES_CABELO = intArrayOf(
            Color.parseColor("#1A1A1A"), // Preto
            Color.parseColor("#3B2314"), // Castanho escuro
            Color.parseColor("#8B5A2B"), // Castanho claro
            Color.parseColor("#E8C27A"), // Loiro
            Color.parseColor("#B84A24"), // Ruivo
            Color.parseColor("#B0B0B0"), // Grisalho
            Color.parseColor("#3B6FD6"), // Azul
            Color.parseColor("#4CAF50"), // Verde
            Color.parseColor("#8B5CF6"), // Roxo
            Color.parseColor("#EC6FBB")  // Rosa
        )

        // Mesma ordem de R.array.ia_cores_olhos_nomes.
        private val CORES_OLHOS = intArrayOf(
            Color.parseColor("#3B2314"), // Castanho escuro
            Color.parseColor("#8B5A2B"), // Castanho claro
            Color.parseColor("#4A90D9"), // Azul
            Color.parseColor("#4CAF50"), // Verde
            Color.parseColor("#9E9E9E"), // Cinza
            Color.parseColor("#D99A2B"), // Âmbar
            Color.parseColor("#8B5CF6")  // Violeta
        )
    }
}
