package io.carius.lars.ar_flutter_plugin

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.widget.Toast
import com.google.ar.core.*
import com.google.ar.core.exceptions.*
import com.google.ar.sceneform.*
import com.google.ar.sceneform.math.Quaternion
import com.google.ar.sceneform.math.Vector3
import com.google.ar.sceneform.rendering.*
import io.carius.lars.ar_flutter_plugin.serialization.deserializeMatrix4
import io.carius.lars.ar_flutter_plugin.serialization.serializeHitResult
import io.flutter.FlutterInjector
import io.flutter.embedding.engine.loader.FlutterLoader
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.platform.PlatformView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CompletableFuture

internal class AndroidARView(
    private val activity: Activity,
    context: Context,
    messenger: BinaryMessenger,
    id: Int,
    creationParams: Map<String?, Any?>?
) : PlatformView {
    // constants
    private val tag: String = AndroidARView::class.java.name

    // Lifecycle variables
    private var mUserRequestedInstall = true
    private lateinit var activityLifecycleCallbacks: Application.ActivityLifecycleCallbacks
    private val viewContext: Context

    // Platform channels
    private val sessionManagerChannel: MethodChannel = MethodChannel(messenger, "arsession_$id")
    private val objectManagerChannel: MethodChannel = MethodChannel(messenger, "arobjects_$id")
    private val anchorManagerChannel: MethodChannel = MethodChannel(messenger, "aranchors_$id")
    private val initialPositionManagerChannel: MethodChannel =
        MethodChannel(messenger, "initialPosition")

    // UI variables
    private lateinit var arSceneView: ArSceneView
    private var worldOriginNode = Node()

    //Added later
    private var isCubePlaced = false
    private var isPointerPlaced = false

    // Model builder
    private var modelBuilder = ArModelBuilder()

    private var firstPlane: Plane? = null

    private var pointerRotation: FloatArray? = null
    private var pointerTranslation: FloatArray? = null

    private var nodeMap: HashMap<String, Any>? = null
    private var cubeNode: Node? = null


    // Method channel handlers
    private val onSessionMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            Log.d(tag, "AndroidARView onsessionmethodcall reveived a call!")
            when (call.method) {
                "init" -> {
                    initializeARView(call, result)
                }
                "snapshot" -> {
                    val bitmap = Bitmap.createBitmap(
                        arSceneView.width, arSceneView.height,
                        Bitmap.Config.ARGB_8888
                    )

                    // Create a handler thread to offload the processing of the image.
                    val handlerThread = HandlerThread("PixelCopier")
                    handlerThread.start()
                    // Make the request to copy.
                    PixelCopy.request(arSceneView, bitmap, { copyResult: Int ->
                        Log.d(tag, "PIXELCOPY DONE")
                        if (copyResult == PixelCopy.SUCCESS) {
                            try {
                                val mainHandler = Handler(context.mainLooper)
                                val runnable = Runnable {
                                    val stream = ByteArrayOutputStream()
                                    bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
                                    val data = stream.toByteArray()
                                    result.success(data)
                                }
                                mainHandler.post(runnable)
                            } catch (e: IOException) {
                                result.error(null.toString(), e.message, e.stackTrace)
                            }
                        } else {
                            result.error(null.toString(), "failed to take screenshot", null)
                        }
                        handlerThread.quitSafely()
                    }, Handler(handlerThread.looper))
                }
                else -> {}
            }
        }
    private val onObjectMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            Log.d(tag, "AndroidARView onobjectmethodcall reveived a call!")
            when (call.method) {
                "init" -> {

                }
                "addNode" -> {
                    if (!isCubePlaced) {
                        val dictNode: HashMap<String, Any>? =
                            call.arguments as? HashMap<String, Any>
                        dictNode?.let {
                            addNode(it).thenAccept { status: Boolean ->
                                isCubePlaced = true
                                arSceneView.planeRenderer.isVisible = false
                                removeSquareAnchor()
                                result.success(status)
                            }.exceptionally { throwable ->
                                result.error(null.toString(), throwable.message, throwable.stackTrace)
                                null
                            }
                        }
                    }
                }
                "addNodeToPlaneAnchor" -> {
                    val dictNode: HashMap<String, Any>? =
                        call.argument<HashMap<String, Any>>("node")
                    val dictAnchor: HashMap<String, Any>? =
                        call.argument<HashMap<String, Any>>("anchor")
                    if (dictNode != null && dictAnchor != null) {
                        addNode(dictNode, dictAnchor).thenAccept { status: Boolean ->
                            isCubePlaced = true
                            arSceneView.planeRenderer.isVisible = false
                            result.success(status)
                        }.exceptionally { throwable ->
                            result.error(null.toString(), throwable.message, throwable.stackTrace)
                            null
                        }
                    } else {
                        result.success(false)
                    }

                }
                "removeNode" -> {
                    val nodeName: String? = call.argument<String>("name")
                    nodeName?.let {
                        val node = arSceneView.scene.findByName(nodeName)
                        node?.let {
                            arSceneView.scene.removeChild(node)
                            isCubePlaced = false
                            arSceneView.planeRenderer.isVisible = false
                            result.success(null)
                        }
                    }
                }
                "transformationChanged" -> {
                    val nodeName: String? = call.argument<String>("name")
                    val newTransformation: ArrayList<Double>? =
                        call.argument<ArrayList<Double>>("transformation")
                    nodeName?.let { name ->
                        newTransformation?.let { transform ->
                            transformNode(name, transform)
                            result.success(null)
                        }
                    }
                }
                else -> {}
            }
        }
    private val initialPositionMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            Log.d(tag, "Print reveived a call!")
            when (call.method) {
                "position-x" -> {
                    val x = pointerTranslation?.get(0)?.toDouble()
                    result.success(x)
                }
                "position-y" -> {
                    val y = pointerTranslation?.get(1)?.toDouble()
                    result.success(y)
                }
                "position-z" -> {
                    val z = pointerTranslation?.get(2)?.toDouble()
                    result.success(z)
                }

                "rotation-x" -> {
                    val x = pointerRotation?.get(0)?.toDouble()
                    result.success(x)
                }
                "rotation-y" -> {
                    val y = pointerRotation?.get(1)?.toDouble()
                    result.success(y)
                }
                "rotation-z" -> {
                    val z = pointerRotation?.get(2)?.toDouble()
                    result.success(z)
                }
                else -> {}
            }
        }
    private val onAnchorMethodCall =
        MethodChannel.MethodCallHandler { call, result ->
            when (call.method) {
                "addAnchor" -> {
                    val anchorType: Int? = call.argument<Int>("type")
                    if (anchorType != null) {
                        when (anchorType) {
                            0 -> { // Plane Anchor
                                val transform: ArrayList<Double>? =
                                    call.argument<ArrayList<Double>>("transformation")
                                val name: String? = call.argument<String>("name")
                                if (name != null && transform != null) {
                                    result.success(addPlaneAnchor(transform, name))
                                } else {
                                    result.success(false)
                                }

                            }
                            else -> result.success(false)
                        }
                    } else {
                        result.success(false)
                    }
                }
                "removeAnchor" -> {
                    val anchorName: String? = call.argument<String>("name")
                    anchorName?.let { name ->
                        removeAnchor(name)
                    }
                }
                else -> {}
            }
        }

    override fun getView(): View {
        return arSceneView
    }

    override fun dispose() {
        // Destroy AR session
        Log.d(tag, "dispose called")
        try {
            onPause()
            arSceneView.destroy()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    init {
        Log.d(tag, "Initializing AndroidARView")
        viewContext = context

        arSceneView = ArSceneView(context)

        setupLifeCycle()

        sessionManagerChannel.setMethodCallHandler(onSessionMethodCall)
        objectManagerChannel.setMethodCallHandler(onObjectMethodCall)
        anchorManagerChannel.setMethodCallHandler(onAnchorMethodCall)
        initialPositionManagerChannel.setMethodCallHandler(initialPositionMethodCall)

        onResume() // call onResume once to setup initial session
    }

    private fun setupLifeCycle() {
        activityLifecycleCallbacks =
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(
                    activity: Activity,
                    savedInstanceState: Bundle?
                ) {
                    Log.d(tag, "onActivityCreated")
                }

                override fun onActivityStarted(activity: Activity) {
                    Log.d(tag, "onActivityStarted")
                }

                override fun onActivityResumed(activity: Activity) {
                    Log.d(tag, "onActivityResumed")
                    onResume()
                }

                override fun onActivityPaused(activity: Activity) {
                    Log.d(tag, "onActivityPaused")
                    onPause()
                }

                override fun onActivityStopped(activity: Activity) {
                    Log.d(tag, "onActivityStopped")
                }

                override fun onActivitySaveInstanceState(
                    activity: Activity,
                    outState: Bundle
                ) {
                }

                override fun onActivityDestroyed(activity: Activity) {
                    Log.d(tag, "onActivityDestroyed")
                }
            }

        activity.application.registerActivityLifecycleCallbacks(this.activityLifecycleCallbacks)
    }

    fun onResume() {
        // Create session if there is none
        if (arSceneView.session == null) {
            Log.d(tag, "ARSceneView session is null. Trying to initialize")
            try {
                val session: Session? =
                    if (ArCoreApk.getInstance().requestInstall(activity, mUserRequestedInstall) ==
                        ArCoreApk.InstallStatus.INSTALL_REQUESTED
                    ) {
                        Log.d(tag, "Install of ArCore APK requested")
                        null
                    } else {
                        Session(activity)
                    }

                if (session == null) {
                    // Ensures next invocation of requestInstall() will either return
                    // INSTALLED or throw an exception.
                    mUserRequestedInstall = false
                    return
                } else {
                    val config = Config(session)
                    config.updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                    config.focusMode = Config.FocusMode.AUTO
                    session.configure(config)
                    arSceneView.setupSession(session)
                }
            } catch (ex: UnavailableUserDeclinedInstallationException) {
                // Display an appropriate message to the user zand return gracefully.
                Toast.makeText(
                    activity,
                    "TODO: handle exception " + ex.localizedMessage,
                    Toast.LENGTH_LONG
                )
                    .show()
                return
            } catch (ex: UnavailableArcoreNotInstalledException) {
                Toast.makeText(activity, "Please install ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableApkTooOldException) {
                Toast.makeText(activity, "Please update ARCore", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableSdkTooOldException) {
                Toast.makeText(activity, "Please update this app", Toast.LENGTH_LONG).show()
                return
            } catch (ex: UnavailableDeviceNotCompatibleException) {
                Toast.makeText(activity, "This device does not support AR", Toast.LENGTH_LONG)
                    .show()
                return
            } catch (e: Exception) {
                Toast.makeText(activity, "Failed to create AR session", Toast.LENGTH_LONG).show()
                return
            }
        }

        try {
            arSceneView.resume()
        } catch (ex: CameraNotAvailableException) {
            Log.d(tag, "Unable to get camera: $ex")
            activity.finish()
            return
        }
    }

    fun onPause() {
        arSceneView.pause()
    }

    private fun initializeARView(call: MethodCall, result: MethodChannel.Result) {
        // Unpack call arguments
        val argPlaneDetectionConfig: Int? = call.argument<Int>("planeDetectionConfig")
        nodeMap = call.argument<HashMap<String, Any>>("nodeMap")
        val argShowPlanes: Boolean? = call.argument<Boolean>("showPlanes")
        val argCustomPlaneTexturePath: String? = call.argument<String>("customPlaneTexturePath")
        val argShowWorldOrigin: Boolean? = call.argument<Boolean>("showWorldOrigin")
        val argHandleTaps: Boolean? = call.argument<Boolean>("handleTaps")

        arSceneView.scene.addOnUpdateListener { frameTime: FrameTime -> onFrame(frameTime) }

        // Configure plane detection
        val config = arSceneView.session?.config
        if (config == null) {
            sessionManagerChannel.invokeMethod("onError", listOf("session is null"))
        }
        when (argPlaneDetectionConfig) {
            1 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL
            }
            2 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.VERTICAL
            }
            3 -> {
                config?.planeFindingMode = Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL
            }
            else -> {
                config?.planeFindingMode = Config.PlaneFindingMode.DISABLED
            }
        }
        arSceneView.session?.configure(config)

        // Configure whether or not detected planes should be shown
        arSceneView.planeRenderer.isVisible = argShowPlanes!!
        // Create custom plane renderer (use supplied texture & increase radius)
        argCustomPlaneTexturePath?.let {
            val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
            val key: String = loader.getLookupKeyForAsset(it)

            val sampler =
                Texture.Sampler.builder()
                    .setMinFilter(Texture.Sampler.MinFilter.LINEAR)
                    .setWrapMode(Texture.Sampler.WrapMode.REPEAT)
                    .build()
            Texture.builder()
                .setSource(viewContext, Uri.parse(key))
                .setSampler(sampler)
                .build()
                .thenAccept { texture: Texture? ->
                    arSceneView.planeRenderer.material.thenAccept { material: Material ->
                        material.setTexture(PlaneRenderer.MATERIAL_TEXTURE, texture)
                        material.setFloat(PlaneRenderer.MATERIAL_SPOTLIGHT_RADIUS, 10f)
                    }
                }
            // Set radius to render planes in
            arSceneView.scene.addOnUpdateListener {
                val planeRenderer = arSceneView.planeRenderer
                planeRenderer.material.thenAccept { material: Material ->
                    material.setFloat(
                        PlaneRenderer.MATERIAL_SPOTLIGHT_RADIUS,
                        10f
                    ) // Sets the radius in which to visualize planes
                }
            }
        }

        // Configure world origin
        if (argShowWorldOrigin == true) {
            worldOriginNode = modelBuilder.makeWorldOriginNode(viewContext)
            arSceneView.scene.addChild(worldOriginNode)
        } else {
            worldOriginNode.setParent(null)
        }

        // Configure Tap handling
        if (argHandleTaps == true) { // explicit comparison necessary because of nullable type
            arSceneView.scene.setOnTouchListener { hitTestResult: HitTestResult, motionEvent: MotionEvent? ->
                onTap(
                    hitTestResult,
                    motionEvent
                )
            }
        }

        result.success(null)
    }


    private fun onFrame(frameTime: FrameTime) {
        val frame = arSceneView.arFrame!!

        val var3 = frame.getUpdatedTrackables(Plane::class.java).iterator()

        if (!var3.hasNext())
            return

        if (isCubePlaced)
            return

        while (var3.hasNext()) {

            firstPlane = var3.next() as Plane


            if (firstPlane?.trackingState == TrackingState.TRACKING) {
                pointerRotation = firstPlane?.centerPose?.rotationQuaternion
                pointerTranslation =
                    frame.camera.pose.compose(Pose.makeTranslation(0f, 0f, -2f)).translation

                val pose = Pose(pointerTranslation, pointerRotation)

                /// Creating the pointer
                if (isPointerPlaced) {
                    cubeNode?.worldPosition = Vector3(
                        pointerTranslation?.get(0)!!,
                        pointerTranslation?.get(1)!!,
                        pointerTranslation?.get(2)!!
                    )
                    cubeNode?.worldRotation = Quaternion(
                        Vector3(
                            pointerRotation?.get(0)!!,
                            pointerRotation?.get(1)!!,
                            pointerRotation?.get(2)!!
                        )
                    )
                } else {
                    removeSquareAnchor()
                    isPointerPlaced = true
                    val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
                    val key: String = loader.getLookupKeyForAsset(nodeMap!!["uri"] as String)

                    modelBuilder.makeNodeFromGltf(
                        viewContext,
                        nodeMap!!["name"] as String,
                        key,
                        nodeMap!!["transformation"] as ArrayList<Double>
                    )
                        .thenAccept { node ->
                            isPointerPlaced = true
                            val anchor = arSceneView.session?.createAnchor(pose)
                            val anchorNode = AnchorNode(anchor)
                            anchorNode.name = "square"
                            anchorNode.setParent(arSceneView.scene)
                            node.setParent(anchorNode)
                            cubeNode = node

                        }.exceptionally {
                            isPointerPlaced = false
                            null
                        }
                }

            }
        }
    }

    private fun removeSquareAnchor() {
        val anchorNode = arSceneView.scene.findByName("square") as AnchorNode?
        anchorNode?.let {
            // Remove corresponding anchor from tracking
            anchorNode.anchor?.detach()
            // Remove children
            for (node in anchorNode.children) {
                node.setParent(null)
            }
            // Remove anchor node
            anchorNode.setParent(null)
            isPointerPlaced = false
        }
    }

    private fun addNode(
        dict_node: HashMap<String, Any>,
        dict_anchor: HashMap<String, Any>? = null
    ): CompletableFuture<Boolean> {
        val completableFutureSuccess: CompletableFuture<Boolean> = CompletableFuture()

        try {
            when (dict_node["type"] as Int) {
                0 -> { // GLTF2 Model from Flutter asset folder
                    // Get path to given Flutter asset
                    val loader: FlutterLoader = FlutterInjector.instance().flutterLoader()
                    val key: String = loader.getLookupKeyForAsset(dict_node["uri"] as String)

                    // Add object to scene
                    modelBuilder.makeNodeFromGltf(
                        viewContext,
                        dict_node["name"] as String,
                        key,
                        dict_node["transformation"] as ArrayList<Double>
                    )
                        .thenAccept { node ->
                            val anchorName: String? = dict_anchor?.get("name") as? String
                            val anchorType: Int? = dict_anchor?.get("type") as? Int
                            if (anchorName != null && anchorType != null) {
                                val anchorNode =
                                    arSceneView.scene.findByName(anchorName) as AnchorNode?
                                if (anchorNode != null) {
                                    anchorNode.addChild(node)
                                    arSceneView.scene.addChild(node)
                                    node.worldPosition = Vector3(
                                        pointerTranslation?.get(0)!!,
                                        pointerTranslation?.get(1)!!,
                                        pointerTranslation?.get(2)!!
                                    )
                                    node.worldRotation = Quaternion(
                                        Vector3(
                                            pointerRotation?.get(0)!!,
                                            pointerRotation?.get(1)!!,
                                            pointerRotation?.get(2)!!
                                        )
                                    )
                                } else {
                                    completableFutureSuccess.complete(false)
                                }
                            } else {
                                arSceneView.scene.addChild(node)
                                node.worldPosition = Vector3(
                                    pointerTranslation?.get(0)!!,
                                    pointerTranslation?.get(1)!!,
                                    pointerTranslation?.get(2)!!
                                )
                                node.worldRotation = Quaternion(
                                    Vector3(
                                        pointerRotation?.get(0)!!,
                                        pointerRotation?.get(1)!!,
                                        pointerRotation?.get(2)!!
                                    )
                                )
                            }
                            completableFutureSuccess.complete(true)
                        }
                        .exceptionally { throwable ->
                            // Pass error to session manager (this has to be done on the main thread if this activity)
                            val mainHandler = Handler(viewContext.mainLooper)
                            val runnable = Runnable {
                                sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf("Unable to load renderable" + dict_node["uri"] as String)
                                )
                            }
                            mainHandler.post(runnable)
                            completableFutureSuccess.completeExceptionally(throwable)
                            null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                        }
                }
                1 -> { // GLB Model from the web
                    modelBuilder.makeNodeFromGlb(
                        viewContext,
                        dict_node["name"] as String,
                        dict_node["uri"] as String,
                        dict_node["transformation"] as ArrayList<Double>
                    )
                        .thenAccept { node ->
                            val anchorName: String? = dict_anchor?.get("name") as? String
                            val anchorType: Int? = dict_anchor?.get("type") as? Int
                            if (anchorName != null && anchorType != null) {
                                val anchorNode =
                                    arSceneView.scene.findByName(anchorName) as AnchorNode?
                                if (anchorNode != null) {
                                    anchorNode.addChild(node)
                                } else {
                                    completableFutureSuccess.complete(false)
                                }
                            } else {
                                arSceneView.scene.addChild(node)
                            }
                            completableFutureSuccess.complete(true)
                        }
                        .exceptionally { throwable ->
                            // Pass error to session manager (this has to be done on the main thread if this activity)
                            val mainHandler = Handler(viewContext.mainLooper)
                            val runnable = Runnable {
                                sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf("Unable to load renderable" + dict_node["uri"] as String)
                                )
                            }
                            mainHandler.post(runnable)
                            completableFutureSuccess.completeExceptionally(throwable)
                            null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                        }
                }
                2 -> { // fileSystemAppFolderGLB
                    val documentsPath = viewContext.applicationInfo.dataDir
                    val assetPath = documentsPath + "/app_flutter/" + dict_node["uri"] as String

                    modelBuilder.makeNodeFromGlb(
                        viewContext,
                        dict_node["name"] as String,
                        assetPath,
                        dict_node["transformation"] as ArrayList<Double>
                    ) //
                        .thenAccept { node ->
                            val anchorName: String? = dict_anchor?.get("name") as? String
                            val anchorType: Int? = dict_anchor?.get("type") as? Int
                            if (anchorName != null && anchorType != null) {
                                val anchorNode =
                                    arSceneView.scene.findByName(anchorName) as AnchorNode?
                                if (anchorNode != null) {
                                    anchorNode.addChild(node)
                                } else {
                                    completableFutureSuccess.complete(false)
                                }
                            } else {
                                arSceneView.scene.addChild(node)
                            }
                            completableFutureSuccess.complete(true)
                        }
                        .exceptionally { throwable ->
                            // Pass error to session manager (this has to be done on the main thread if this activity)
                            val mainHandler = Handler(viewContext.mainLooper)
                            val runnable = Runnable {
                                sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf("Unable to load renderable " + dict_node["uri"] as String)
                                )
                            }
                            mainHandler.post(runnable)
                            completableFutureSuccess.completeExceptionally(throwable)
                            null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                        }
                }
                3 -> { //fileSystemAppFolderGLTF2
                    // Get path to given Flutter asset
                    val documentsPath = viewContext.applicationInfo.dataDir
                    val assetPath = documentsPath + "/app_flutter/" + dict_node["uri"] as String

                    // Add object to scene
                    modelBuilder.makeNodeFromGltf(
                        viewContext,
                        dict_node["name"] as String,
                        assetPath,
                        dict_node["transformation"] as ArrayList<Double>
                    )
                        .thenAccept { node ->
                            val anchorName: String? = dict_anchor?.get("name") as? String
                            val anchorType: Int? = dict_anchor?.get("type") as? Int
                            if (anchorName != null && anchorType != null) {
                                val anchorNode =
                                    arSceneView.scene.findByName(anchorName) as AnchorNode?
                                if (anchorNode != null) {
                                    anchorNode.addChild(node)
                                } else {
                                    completableFutureSuccess.complete(false)
                                }
                            } else {
                                arSceneView.scene.addChild(node)
                            }
                            completableFutureSuccess.complete(true)
                        }
                        .exceptionally { throwable ->
                            // Pass error to session manager (this has to be done on the main thread if this activity)
                            val mainHandler = Handler(viewContext.mainLooper)
                            val runnable = Runnable {
                                sessionManagerChannel.invokeMethod(
                                    "onError",
                                    listOf("Unable to load renderable" + dict_node["uri"] as String)
                                )
                            }
                            mainHandler.post(runnable)
                            completableFutureSuccess.completeExceptionally(throwable)
                            null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
                        }
                }
                else -> {
                    completableFutureSuccess.complete(false)
                }
            }
        } catch (e: java.lang.Exception) {
            completableFutureSuccess.completeExceptionally(e)
        }

        return completableFutureSuccess
    }

    private fun transformNode(name: String, transform: ArrayList<Double>) {
        val node = arSceneView.scene.findByName(name)
        node?.let {
            val transformTriple = deserializeMatrix4(transform)
            it.worldScale = transformTriple.first
            it.worldPosition = transformTriple.second
            it.worldRotation = transformTriple.third
        }
    }

    private fun onTap(hitTestResult: HitTestResult, motionEvent: MotionEvent?): Boolean {
        val frame = arSceneView.arFrame

        if (hitTestResult.node != null && motionEvent?.action == MotionEvent.ACTION_MOVE) {
            val allHitResults = frame?.hitTest(motionEvent) ?: listOf<HitResult>()
            val serializedHitResults: ArrayList<HashMap<String, Any>> =
                ArrayList(allHitResults.map { serializeHitResult(it) })
            objectManagerChannel.invokeMethod("onNodeTap", serializedHitResults)
            return true
        }
        if (motionEvent != null && motionEvent.action == MotionEvent.ACTION_MOVE) {
            val allHitResults = frame?.hitTest(motionEvent) ?: listOf<HitResult>()
            val planeAndPointHitResults =
                allHitResults.filter { ((it.trackable is Plane) || (it.trackable is Point)) }
            val serializedPlaneAndPointHitResults: ArrayList<HashMap<String, Any>> =
                ArrayList(planeAndPointHitResults.map { serializeHitResult(it) })
            sessionManagerChannel.invokeMethod(
                "onPlaneOrPointTap",
                serializedPlaneAndPointHitResults
            )
            return true
        }
        if (motionEvent != null && motionEvent.action == MotionEvent.ACTION_DOWN) {
            val allHitResults = frame?.hitTest(motionEvent) ?: listOf<HitResult>()
            val planeAndPointHitResults =
                allHitResults.filter { ((it.trackable is Plane) || (it.trackable is Point)) }
            val serializedPlaneAndPointHitResults: ArrayList<HashMap<String, Any>> =
                ArrayList(planeAndPointHitResults.map { serializeHitResult(it) })
            sessionManagerChannel.invokeMethod(
                "onPlaneOrPointTap",
                serializedPlaneAndPointHitResults
            )
            return true
        }
        return false
    }

    private fun addPlaneAnchor(transform: ArrayList<Double>, name: String): Boolean {
        return try {
            val position = floatArrayOf(
                deserializeMatrix4(transform).second.x,
                deserializeMatrix4(transform).second.y,
                deserializeMatrix4(transform).second.z
            )
            val rotation = floatArrayOf(
                deserializeMatrix4(transform).third.x,
                deserializeMatrix4(transform).third.y,
                deserializeMatrix4(transform).third.z,
                deserializeMatrix4(transform).third.w
            )
            val anchor: Anchor = arSceneView.session!!.createAnchor(Pose(position, rotation))
            val anchorNode = AnchorNode(anchor)
            anchorNode.name = name
            anchorNode.setParent(arSceneView.scene)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun removeAnchor(name: String) {
        val anchorNode = arSceneView.scene.findByName(name) as AnchorNode?
        anchorNode?.let {
            // Remove corresponding anchor from tracking
            anchorNode.anchor?.detach()
            // Remove children
            for (node in anchorNode.children) {
                node.setParent(null)
            }
            // Remove anchor node
            anchorNode.setParent(null)
        }
    }

}
