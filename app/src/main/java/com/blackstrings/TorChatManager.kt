package com.blackstrings

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class TorChatManager(
    private val context: Context,
    private val callback: Callback
) {
    interface Callback {
        fun onTorConnected(myOnion: String)
        fun onPeerConnected(onion: String)
        fun onMessageReceived(text: String)
        fun onMessageSent(text: String)
        fun onError(err: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newCachedThreadPool()
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var torProcess: Process? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var peerSocket: Socket? = null

    @Volatile
    private var peerWriter: PrintWriter? = null

    private fun sendToHtml(action: () -> Unit) {
        mainHandler.post(action)
    }

    fun startTor() {
        if (isRunning.getAndSet(true)) {
            return
        }

        executor.execute {
            try {
                val torBinary = TorService.prepareTorBinary(context)
                val torDataDir = File(context.filesDir, "tor_data").apply { mkdirs() }
                val hiddenServiceDir = File(context.filesDir, "hidden_service").apply { mkdirs() }

                setOwnerOnlyPermissions(torDataDir)
                setOwnerOnlyPermissions(hiddenServiceDir)

                val torrcFile = File(context.filesDir, "torrc")
                val torrcContent = """
                    SocksPort 127.0.0.1:9050
                    ControlPort 127.0.0.1:9051
                    CookieAuthentication 0
                    DataDirectory ${torDataDir.absolutePath}
                    HiddenServiceDir ${hiddenServiceDir.absolutePath}
                    HiddenServicePort 80 127.0.0.1:8080
                """.trimIndent()
                torrcFile.writeText(torrcContent)

                val process = ProcessBuilder(
                    torBinary.absolutePath,
                    "-f",
                    torrcFile.absolutePath
                )
                    .redirectErrorStream(true)
                    .start()

                torProcess = process

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var bootstrapped = false
                var line: String?

                while (isRunning.get() && reader.readLine().also { line = it } != null) {
                    val logLine = line ?: continue
                    if (logLine.contains("Bootstrapped 100%")) {
                        bootstrapped = true
                        break
                    }
                }

                if (!bootstrapped) {
                    val exitCode = try {
                        process.exitValue()
                    } catch (_: IllegalThreadStateException) {
                        -1
                    }
                    isRunning.set(false)
                    sendToHtml {
                        callback.onError("Tor failed to bootstrap (exit code: $exitCode)")
                    }
                    return@execute
                }

                // Keep draining Tor stdout so the process buffer never blocks
                executor.execute {
                    try {
                        while (isRunning.get() && reader.readLine() != null) {
                            // Drain output
                        }
                    } catch (_: Exception) {
                    }
                }

                val hostnameFile = File(hiddenServiceDir, "hostname")
                var attempts = 0
                while (!hostnameFile.exists() && attempts < 30) {
                    Thread.sleep(250)
                    attempts++
                }

                if (!hostnameFile.exists()) {
                    sendToHtml { callback.onError("Hidden service hostname not generated") }
                    return@execute
                }

                val myOnion = hostnameFile.readText().trim()
                if (myOnion.isEmpty()) {
                    sendToHtml { callback.onError("Empty .onion hostname") }
                    return@execute
                }

                startListening()
                sendToHtml { callback.onTorConnected(myOnion) }
            } catch (e: Exception) {
                isRunning.set(false)
                sendToHtml { callback.onError(e.message ?: "Failed to start Tor") }
            }
        }
    }

    private fun startListening() {
        executor.execute {
            try {
                serverSocket?.close()
                val server = ServerSocket(8080)
                serverSocket = server

                while (isRunning.get() && !server.isClosed) {
                    val client = server.accept()
                    handleIncomingSocket(client)
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    sendToHtml { callback.onError("Listener error: ${e.message}") }
                }
            }
        }
    }

    private fun handleIncomingSocket(socket: Socket) {
        executor.execute {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                var line: String?
                while (isRunning.get() && reader.readLine().also { line = it } != null) {
                    val message = line ?: continue
                    if (message.isNotEmpty()) {
                        sendToHtml { callback.onMessageReceived(message) }
                    }
                }
            } catch (e: Exception) {
                if (isRunning.get()) {
                    sendToHtml { callback.onError("Incoming read error: ${e.message}") }
                }
            } finally {
                try {
                    socket.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    fun connectToPeer(onion: String) {
        executor.execute {
            try {
                val cleanOnion = onion.trim()
                    .removePrefix("http://")
                    .removePrefix("https://")
                    .substringBefore("/")
                    .substringBefore(":")

                if (!cleanOnion.endsWith(".onion")) {
                    sendToHtml { callback.onError("Invalid .onion address") }
                    return@execute
                }

                peerWriter?.close()
                peerSocket?.close()

                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 9050))
                val socket = Socket(proxy)
                socket.connect(InetSocketAddress.createUnresolved(cleanOnion, 80), 30000)

                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                peerSocket = socket
                peerWriter = writer

                // Also listen for replies on this connected socket
                handleIncomingSocket(socket)

                sendToHtml { callback.onPeerConnected(cleanOnion) }
            } catch (e: Exception) {
                peerWriter = null
                peerSocket = null
                sendToHtml { callback.onError("Peer connect failed: ${e.message}") }
            }
        }
    }

    fun sendMessage(msg: String) {
        executor.execute {
            try {
                val writer = peerWriter
                if (writer == null) {
                    sendToHtml { callback.onError("Connect to friend first") }
                    return@execute
                }

                writer.println(msg)
                writer.flush()

                if (writer.checkError()) {
                    sendToHtml { callback.onError("Failed to send message; peer disconnected") }
                    return@execute
                }

                sendToHtml { callback.onMessageSent(msg) }
            } catch (e: Exception) {
                sendToHtml { callback.onError("Send error: ${e.message}") }
            }
        }
    }

    private fun setOwnerOnlyPermissions(dir: File) {
        dir.setReadable(false, false)
        dir.setWritable(false, false)
        dir.setExecutable(false, false)
        dir.setReadable(true, true)
        dir.setWritable(true, true)
        dir.setExecutable(true, true)
    }

    fun stop() {
        isRunning.set(false)
        try {
            peerWriter?.close()
        } catch (_: Exception) {
        }
        try {
            peerSocket?.close()
        } catch (_: Exception) {
        }
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        try {
            torProcess?.destroy()
        } catch (_: Exception) {
        }
        executor.shutdownNow()
    }
}
