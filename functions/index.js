// Carrega variáveis de ambiente do arquivo .env (se existir) — mesmo
// padrão do Match/Caronas: NUNCA committar esse arquivo (ver .gitignore).
require('dotenv').config();

const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

// ============================================================
// IA na nuvem — proxy pra Replicate (https://replicate.com). O app manda a
// foto em base64 direto no payload da Cloud Function (sem Firebase Storage:
// não tem histórico/conta nessa v1, é processar e devolver), a Function
// chama a Replicate com a chave de API que SÓ existe aqui no servidor
// (nunca no app — mesmo motivo do proxy do LocationIQ no Caronas e do
// Google Maps no Match: a chave dentro do APK seria extraível por
// engenharia reversa e qualquer um gastaria a cota/o saldo pago).
//
// Fluxo assíncrono em 2 chamadas (mesmo padrão de createPaymentPreference-
// Motorista/checkPaymentStatusMotorista no Caronas): iniciarProcessamento
// devolve na hora um predictionId; o app fica chamando verificarProcessamento
// de tempos em tempos até o status virar "succeeded" — processamento de IA
// generativa pode levar de alguns segundos a mais de um minuto, tempo
// demais pra uma Cloud Function ficar bloqueada esperando numa única
// chamada.
// ============================================================
const REPLICATE_API_TOKEN = process.env.REPLICATE_API_TOKEN || '';

if (!REPLICATE_API_TOKEN) {
    console.error('❌ REPLICATE_API_TOKEN NÃO CONFIGURADO!');
    console.error('👉 Crie uma conta em https://replicate.com, gere um token em');
    console.error('   replicate.com/account/api-tokens e coloque no .env da pasta functions:');
    console.error('   REPLICATE_API_TOKEN=SEU_TOKEN');
    console.error('   Enquanto isso não estiver configurado, nenhuma função de IA funciona.');
}

// Cada operação mapeia pro "owner/name" de um modelo público na Replicate —
// chamado via /v1/models/{owner}/{name}/predictions, que sempre usa a
// ÚLTIMA versão publicada do modelo (não precisa fixar um hash de versão
// específico, que ficaria desatualizado com o tempo).
//
// IMPORTANTE: os nomes dos parâmetros de "input" de cada modelo (montados
// em montarInputParaOperacao) foram levantados via pesquisa (páginas
// replicate.com/<modelo> e material de terceiros) — antes de usar de
// verdade, confirme o schema exato na aba "API" de cada página do modelo
// (replicate.com/<owner>/<nome>/api), porque a Replicate não garante que
// esses nomes nunca mudem entre versões.
const REPLICATE_MODELOS = {
    // Restauração básica (mesmo espírito do "Restaurar" local, só que com
    // IA de verdade por trás).
    restaurar_rosto: 'tencentarc/gfpgan',
    colorizar: 'piddnad/ddcolor',
    aumentar_resolucao: 'nightmareai/real-esrgan',

    // Fundo.
    remover_fundo: '851-labs/background-remover',

    // Segmentação (cabelo/olhos/roupa/pele/fundo num único mask — usada
    // como base pra recolorir cabelo/olhos/roupa do lado do app, sem
    // precisar de outra chamada de IA por cor escolhida).
    segmentar: 'ahmdyassr/mask-clothing',

    // Troca de peça de roupa (não só cor — a peça inteira, ver
    // montarInputParaOperacao). Modelo pesado, mais caro/lento que os
    // outros.
    trocar_roupa: 'cuuupid/idm-vton',

    // Estilos artísticos (pintura, desenho, lápis, vintage, retrato
    // profissional…) — um modelo só, o "estilo" é escolhido via prompt
    // (ver PROMPTS_ESTILO).
    estilizar: 'stability-ai/stable-diffusion-img2img',
};

// Presets de prompt pro botão "Estilizar" — cobre a lista original de
// estilos do documento que deu origem ao projeto (Restart.docx): pintura
// artística, retrato profissional, desenho, lápis, vintage. Fácil de somar
// novos estilos aqui sem mexer em mais nada.
const PROMPTS_ESTILO = {
    pintura: 'oil painting portrait, fine art brushstrokes, detailed, museum quality, canvas texture',
    retrato: 'professional studio portrait photography, sharp focus, soft studio lighting, high detail',
    desenho: 'detailed pencil sketch drawing, black and white line art, hand drawn illustration',
    lapis: 'graphite pencil drawing, hand drawn, cross hatching shading, sketchbook style',
    vintage: 'vintage film photograph, warm sepia tones, 1970s aesthetic, grainy film texture',
};

async function criarPrediction(modelo, input) {
    const resposta = await fetch(`https://api.replicate.com/v1/models/${modelo}/predictions`, {
        method: 'POST',
        headers: {
            Authorization: `Bearer ${REPLICATE_API_TOKEN}`,
            'Content-Type': 'application/json',
        },
        body: JSON.stringify({ input }),
    });
    if (!resposta.ok) {
        const corpoErro = await resposta.text();
        throw new Error(`Replicate respondeu ${resposta.status}: ${corpoErro}`);
    }
    return resposta.json();
}

// Monta o "input" certo pra cada modelo — cada um espera um formato
// diferente de parâmetros (ver comentário grande acima sobre confirmar o
// schema real antes de usar em produção).
function montarInputParaOperacao(operacao, imagemBase64, extras) {
    switch (operacao) {
        case 'restaurar_rosto':
            return { img: imagemBase64 };

        case 'colorizar':
            return { image: imagemBase64 };

        case 'aumentar_resolucao':
            return { image: imagemBase64, scale: extras.escala || 2 };

        case 'remover_fundo':
            return { image: imagemBase64 };

        case 'segmentar':
            return { image: imagemBase64 };

        case 'trocar_roupa': {
            if (!extras.roupaBase64) {
                throw new functions.https.HttpsError(
                    'invalid-argument',
                    'roupaBase64 (foto da peça de roupa desejada) é obrigatória pra trocar_roupa.'
                );
            }
            const categoria = extras.categoria || 'upper_body';
            if (!['upper_body', 'lower_body', 'dresses'].includes(categoria)) {
                throw new functions.https.HttpsError(
                    'invalid-argument',
                    "categoria precisa ser 'upper_body', 'lower_body' ou 'dresses'."
                );
            }
            return { human_img: imagemBase64, garm_img: extras.roupaBase64, category: categoria };
        }

        case 'estilizar': {
            const prompt = PROMPTS_ESTILO[extras.estilo] || extras.promptPersonalizado;
            if (!prompt) {
                throw new functions.https.HttpsError(
                    'invalid-argument',
                    `estilo desconhecido e sem promptPersonalizado (opções: ${Object.keys(PROMPTS_ESTILO).join(', ')}).`
                );
            }
            return {
                image: imagemBase64,
                prompt,
                strength: extras.intensidade || 0.65,
                guidance_scale: 7.5,
            };
        }

        default:
            throw new functions.https.HttpsError('invalid-argument', `Operação desconhecida: ${operacao}`);
    }
}

// Chamado pelo app ao tocar em qualquer botão de efeito de IA — devolve o
// predictionId na hora (não espera o processamento terminar, ver
// verificarProcessamento).
exports.iniciarProcessamento = functions.https.onCall(async (request) => {
    if (!REPLICATE_API_TOKEN) {
        throw new functions.https.HttpsError('failed-precondition', 'IA na nuvem não configurada no servidor.');
    }
    if (!request.auth) {
        throw new functions.https.HttpsError('unauthenticated', 'Usuário não autenticado.');
    }

    const { operacao, imagemBase64, ...extras } = request.data || {};
    const modelo = REPLICATE_MODELOS[operacao];
    if (!modelo) {
        throw new functions.https.HttpsError(
            'invalid-argument',
            `Operação desconhecida: ${operacao}. Válidas: ${Object.keys(REPLICATE_MODELOS).join(', ')}.`
        );
    }
    if (!imagemBase64) {
        throw new functions.https.HttpsError('invalid-argument', 'imagemBase64 é obrigatória.');
    }

    const input = montarInputParaOperacao(operacao, imagemBase64, extras);

    try {
        console.log(`📦 Iniciando "${operacao}" (${modelo}) pra ${request.auth.uid}`);
        const prediction = await criarPrediction(modelo, input);
        return { predictionId: prediction.id, status: prediction.status };
    } catch (error) {
        console.error(`❌ Erro ao iniciar "${operacao}":`, error);
        throw new functions.https.HttpsError('internal', 'Erro ao iniciar processamento: ' + error.message);
    }
});

// Chamado pelo app em polling curto (a cada 2-3s, por exemplo) até o
// status virar "succeeded" ou "failed" — mesmo padrão de
// checkPaymentStatusMotorista no Caronas.
exports.verificarProcessamento = functions.https.onCall(async (request) => {
    if (!REPLICATE_API_TOKEN) {
        throw new functions.https.HttpsError('failed-precondition', 'IA na nuvem não configurada no servidor.');
    }
    if (!request.auth) {
        throw new functions.https.HttpsError('unauthenticated', 'Usuário não autenticado.');
    }

    const { predictionId } = request.data || {};
    if (!predictionId) {
        throw new functions.https.HttpsError('invalid-argument', 'predictionId é obrigatório.');
    }

    try {
        const resposta = await fetch(`https://api.replicate.com/v1/predictions/${predictionId}`, {
            headers: { Authorization: `Bearer ${REPLICATE_API_TOKEN}` },
        });
        if (!resposta.ok) {
            throw new Error(`Replicate respondeu ${resposta.status}`);
        }
        const prediction = await resposta.json();

        return {
            // starting | processing | succeeded | failed | canceled
            status: prediction.status,
            output: prediction.output || null,
            erro: prediction.error || null,
        };
    } catch (error) {
        console.error('❌ Erro ao verificar processamento:', error);
        throw new functions.https.HttpsError('internal', 'Erro ao verificar processamento: ' + error.message);
    }
});
