package io.carius.lars.ar_flutter_plugin

import android.content.Context
import android.net.Uri
import com.google.ar.sceneform.Node
import com.google.ar.sceneform.assets.RenderableSource
import com.google.ar.sceneform.rendering.ModelRenderable
import io.carius.lars.ar_flutter_plugin.serialization.deserializeMatrix4
import java.util.concurrent.CompletableFuture

// Responsible for creating Renderables and Nodes
class ArModelBuilder {

    // Creates a node form a given gltf model path or URL. The gltf asset loading in Scenform is asynchronous, so the function returns a completable future of type Node
    fun makeNodeFromGltf(context: Context, name: String, modelPath: String, transformation: ArrayList<Double>): CompletableFuture<Node> {
        val completableFutureNode: CompletableFuture<Node> = CompletableFuture()

        val gltfNode = Node()

        ModelRenderable.builder()
            .setSource(context, RenderableSource.builder().setSource(
                context,
                Uri.parse(modelPath),
                RenderableSource.SourceType.GLTF2)
                .build())
            .setRegistryId(modelPath)
            .build()
            .thenAccept{ renderable ->
                gltfNode.renderable = renderable
                gltfNode.name = name
                val transform = deserializeMatrix4(transformation)
                gltfNode.worldScale = transform.first
                gltfNode.worldPosition = transform.second
                gltfNode.worldRotation = transform.third
                completableFutureNode.complete(gltfNode)
            }
            .exceptionally { throwable ->
                completableFutureNode.completeExceptionally(throwable)
                null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
            }

        return completableFutureNode
    }

    // Creates a node form a given glb model path or URL. The gltf asset loading in Sceneform is asynchronous, so the function returns a compleatable future of type Node
    fun makeNodeFromGlb(context: Context, name: String, modelPath: String, transformation: ArrayList<Double>): CompletableFuture<Node> {
        val completableFutureNode: CompletableFuture<Node> = CompletableFuture()

        val gltfNode = Node()

        ModelRenderable.builder()
            .setSource(context, RenderableSource.builder().setSource(
                context,
                Uri.parse(modelPath),
                RenderableSource.SourceType.GLB)
                .build())
            .setRegistryId(modelPath)
            .build()
            .thenAccept{ renderable ->
                gltfNode.renderable = renderable
                gltfNode.name = name
                val transform = deserializeMatrix4(transformation)
                gltfNode.worldScale = transform.first
                gltfNode.worldPosition = transform.second
                gltfNode.worldRotation = transform.third
                completableFutureNode.complete(gltfNode)
            }
            .exceptionally{throwable ->
                completableFutureNode.completeExceptionally(throwable)
                null // return null because java expects void return (in java, void has no instance, whereas in Kotlin, this closure returns a Unit which has one instance)
            }

        return completableFutureNode
    }
}