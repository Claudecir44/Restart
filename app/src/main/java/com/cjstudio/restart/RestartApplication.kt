package com.cjstudio.restart

import android.app.Application
import com.google.firebase.auth.FirebaseAuth

// Login anônimo — só serve pra Cloud Functions de IA (ver
// IaNuvemRepository/functions/index.js) saberem que a chamada veio de
// dentro do app de verdade, sem precisar de tela de cadastro/login (o app
// não tem conceito de "conta"/histórico nessa v1). Se o Firebase ainda não
// estiver configurado (sem google-services.json — ver app/build.gradle.kts),
// isso falha silenciosamente e só a seção "IA na nuvem" fica indisponível,
// os efeitos locais continuam funcionando normalmente.
class RestartApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) {
                auth.signInAnonymously()
            }
        } catch (e: Exception) {
            // Firebase não inicializado (google-services.json ausente) —
            // esperado antes do projeto Firebase existir de verdade.
        }
    }
}
