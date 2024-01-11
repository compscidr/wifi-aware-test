package com.jasonernst.wifi_aware_test

import android.Manifest
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.net.*
import android.net.wifi.aware.*
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import logcat.AndroidLogcatLogger
import logcat.LogPriority
import logcat.logcat
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.net.ServerSocket

class MainActivity : AppCompatActivity() {

    private lateinit var wifiAwareManager : WifiAwareManager
    private var wifiAwareSession : WifiAwareSession? = null
    private lateinit var subscribeDiscoverySession : SubscribeDiscoverySession
    private lateinit var publishDiscoverySession: PublishDiscoverySession
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private lateinit var serverSocket: ServerSocket

    private companion object {
        private const val SERVICE_NAME = "wifi-direct-test"
        private const val REQUEST_ACCESS_FINE_LOCATION = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AndroidLogcatLogger.installOnDebuggableApp(application, minPriority = LogPriority.VERBOSE)

        if (applicationContext.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
                .not()
        ) {
            logcat(LogPriority.ERROR) { "wi-fi aware not present on this device" }
            return // todo: update a status or something and do nothing more
        }
        logcat { "wifi-aware is present on this device" }

        // obtain permissions
        if (ContextCompat.checkSelfPermission(applicationContext, ACCESS_FINE_LOCATION) != PERMISSION_GRANTED) {
            logcat { "need access fine location permission" }
            ActivityCompat.requestPermissions(this, arrayOf(ACCESS_FINE_LOCATION), REQUEST_ACCESS_FINE_LOCATION)
            return
        } else {
            logcat { "have fine location permission" }
        }
        init()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_ACCESS_FINE_LOCATION) {
            if (grantResults.size > 0 && grantResults[0] == PERMISSION_GRANTED) {
                logcat { "permission granted"}
                init()
            } else {
                logcat { "permission denied, giving up" }
            }
        } else {
            logcat { "permission denied, giving up" }
        }
    }

    private fun init() {
        setContentView(R.layout.activity_main);
        wifiAwareManager = (applicationContext.getSystemService(Context.WIFI_AWARE_SERVICE) as WifiAwareManager?)!!
        val filter = IntentFilter(WifiAwareManager.ACTION_WIFI_AWARE_STATE_CHANGED)

        // receiver for wifi direct state change
        val myReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // discard current sessions
                if (wifiAwareManager.isAvailable) {
                    logcat { "wifi-aware is available"}
                } else {
                    logcat { "wifi-aware is not available"}
                }
            }
        }
        applicationContext.registerReceiver(myReceiver, filter)

        // receiver for wifi direct session callback
        val sessionCallback = object : AttachCallback() {
            override fun onAttached(session: WifiAwareSession?) {
                super.onAttached(session)
                wifiAwareSession = session
                logcat { "wifi-aware is attached"}
                publish()
                Thread.sleep(1000) // give some time so we don't wind up trying to be both at once
                subscribe()
            }

            override fun onAttachFailed() {
                super.onAttachFailed()
                wifiAwareSession?.close()
                logcat { "wifi-aware attach failed"}
            }
        }

        // use main thread if handler is null - probably change this
        wifiAwareManager.attach(sessionCallback, null)
    }

    private fun publish() {
        val config: PublishConfig = PublishConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        // receiver for publish callback
        val publishCallback = object : DiscoverySessionCallback() {

            override fun onPublishStarted(session: PublishDiscoverySession) {
                logcat { "publish started" }
                publishDiscoverySession = session
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                logcat { "publisher message received" }

                serverSocket = ServerSocket(0)
                val port = serverSocket.localPort

                Thread {
                    logcat { "attempting to accept connection at publisher" }
                    val socket = serverSocket.accept() // blocks
                    logcat { "connection accepted at publisher" }
                    inputStream = socket.getInputStream()
                    outputStream = socket.getOutputStream()
                    readData()
                }.start()

                network(publishDiscoverySession, peerHandle, port)

                publishDiscoverySession.sendMessage(peerHandle, 0, "test".toByteArray())
            }
        }
        // use main thread if null - probably change this
        if (ActivityCompat.checkSelfPermission(
                this,
                ACCESS_FINE_LOCATION
            ) != PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        wifiAwareSession?.publish(config, publishCallback, null)
    }

    private fun subscribe() {
        val config: SubscribeConfig = SubscribeConfig.Builder()
            .setServiceName(SERVICE_NAME)
            .build()

        // receiver for subscribe callback
        val subscribeCallback = object : DiscoverySessionCallback() {

            override fun onSubscribeStarted(session: SubscribeDiscoverySession) {
                logcat { "subscribe started" }
                subscribeDiscoverySession = session
            }

            override fun onServiceDiscovered(peerHandle: PeerHandle, serviceSpecificInfo: ByteArray, matchFilter: List<ByteArray>) {
                logcat { "service discovered" }
                // initiate a connection from the discovery side
                subscribeDiscoverySession.sendMessage(peerHandle, 0, "test".toByteArray())
            }

            override fun onMessageReceived(peerHandle: PeerHandle, message: ByteArray) {
                logcat { "subscriber message received" }
                network(subscribeDiscoverySession, peerHandle, -1)
            }

        }
        // use main thread if null - probably change this
        if (ActivityCompat.checkSelfPermission(
                this,
                ACCESS_FINE_LOCATION
            ) != PERMISSION_GRANTED || ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.NEARBY_WIFI_DEVICES
            ) != PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        wifiAwareSession?.subscribe(config, subscribeCallback, null)
    }

    /**
     * Sets up the Wi-Fi direct network on both the publisher and subscriber side of the service
     */
    private fun network(discoverySession : DiscoverySession, peerHandle : PeerHandle, port: Int) {
        val networkSpecifier = if (port == -1) {
            // item 6 on: https://developer.android.com/guide/topics/connectivity/wifi-aware (don't send a port)
            WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
                .setPskPassphrase("somePassword")
                .build()
        } else {
            // item 4 on: https://developer.android.com/guide/topics/connectivity/wifi-aware (send port the serversocket is listening on)
            WifiAwareNetworkSpecifier.Builder(discoverySession, peerHandle)
                .setPskPassphrase("somePassword")
                .setPort(port)
                .build()
        }

        val myNetworkRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI_AWARE)
            .setNetworkSpecifier(networkSpecifier)
            .build()

        // receiver for the network callback
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                logcat { "Network available" }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                logcat { "Network capabilities changed $network : $networkCapabilities" }

                // item 7: https://developer.android.com/guide/topics/connectivity/wifi-aware
                val peerAwareInfo = networkCapabilities.transportInfo as WifiAwareNetworkInfo
                val peerIpv6 = peerAwareInfo.peerIpv6Addr
                val peerPort = peerAwareInfo.port

                // "creates and connects": https://docs.oracle.com/javase/7/docs/api/javax/net/SocketFactory.html#createSocket(java.net.InetAddress,%20int)
                if (inputStream == null) {
                    Thread {
                        try {
                            logcat { "attempting to make connection from subscriber" }
                            val socket = network.getSocketFactory().createSocket(peerIpv6, peerPort)
                            logcat { "connection made from subscriber" }
                            inputStream = socket.getInputStream()
                            outputStream = socket.getOutputStream()
                            readData()
                        } catch (ex : Exception) {
                            logcat(LogPriority.WARN) { ex.toString() }
                        }
                    }.start()
                } else {
                    logcat { "capabilities changed, but already connected" }
                }
            }

            override fun onLost(network: Network) {
                logcat { "Network lost" }
            }
        }

        val connectivityManager = applicationContext.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.requestNetwork(myNetworkRequest, callback)

    }

    @Override
    override fun onDestroy() {
        super.onDestroy()
        wifiAwareSession?.close()
    }

    fun readData() {
        val bufferedInputStream = BufferedInputStream(inputStream)
        while (true) {
            val available = bufferedInputStream.available()
            if (available > 0) {
                logcat { "Got data" }
                val data = ByteArray(available)
                bufferedInputStream.read(data)
                runOnUiThread {
                    Toast.makeText(applicationContext, String(data), Toast.LENGTH_SHORT).show()
                }
            } else {
                Thread.sleep(1000)
            }
        }
    }

    fun send(v: View) {
        Thread {
            outputStream?.write("DATA".toByteArray())
        }.start()
//        if (isPublisher) {
//            Thread {
//                outputStream?.write("FR PUBLISHER".toByteArray())
//            }.start()
//        } else {
//            Thread {
//                outputStream?.write("FR SUBSCRIBER".toByteArray())
//            }.start()
//        }
    }
}