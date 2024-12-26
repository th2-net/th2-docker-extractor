/*
 * Copyright (c) 2024, Exactpro Systems LLC
 * www.exactpro.com
 * Build Software to Test Software
 *
 * All rights reserved.
 * This is unpublished, licensed software, confidential and proprietary
 * information which is the property of Exactpro Systems LLC or its licensors.
 */

package com.exactpro.th2.tools

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.check
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.jvm.optionals.getOrElse

fun main(args: Array<String>) = Extract().main(args)

private class Extract : CliktCommand() {
    private enum class AuthType { TOKEN, BASIC }

    private val imageSpecString: String by argument()
        .help("IMAGE can be a community user image (like 'some-user/some-image') or a " +
                "Docker official image (like 'hello-world', which contains no '/').")
    // .check("Wrong docker image specification") { dockerImageRegexp.matches(it) } TODO: fix regexp

    private val platformString: String by option("-p").default(DEFAULT_PLATFORM)
        .help("Pull image for the specified platform (default: $DEFAULT_PLATFORM) " +
                "For a given image on Docker Hub, the 'Tags' tab lists the " +
                "platforms supported for that image.")

    private val outDir: Path by option("-o").path().default(Path.of("./output"))
        .help("Extract image to the specified output dir (default: $OUT_DIR)")

    private val numberOfLayers: Int by option("-n").int().default(1)
        .help("number of layers to be extracted, will be taken in reverse order")
        .check("Value must be positive") { it > 0 }

    private val authType: AuthType by option("-t").enum<AuthType>()
        .default(AuthType.TOKEN)
        .help("type of auth. Valid values: token, basic")

    private val jsonMapper = ObjectMapper().registerKotlinModule()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.ALWAYS).build()

    private lateinit var authorizationHeader: String

    override fun run() {

        if (outDir.exists()) {
            if (outDir.isDirectory()) {
                echo("WARNING: Output dir already exists. If it contains a previous extracted image, " +
                        "there may be errors when trying to overwrite files with read-only permissions.")
            } else {
                echo("ERROR: Output dir already exists, but is not a directory.")
                return
            }
        }

        val basicUser: String? = System.getenv("BASIC_USER")
        val basicPassword: String? = System.getenv("BASIC_PASSWORD")

        authorizationHeader = when (authType) {
            AuthType.TOKEN -> "Bearer ${Base64.getEncoder().encodeToString("token".toByteArray())}"
            AuthType.BASIC -> {
                requireNotNull(basicUser) { "Basic authentication username not provided" }
                requireNotNull(basicPassword) { "Basic authentication password not provided" }
                "Basic ${Base64.getEncoder().encodeToString("$basicUser:$basicPassword".toByteArray())}"
            }
        }

        val imageSpec = ImageSpec.createFromString(imageSpecString)
        println("image: $imageSpecString\nplatform: $platformString\noutDir: $outDir\nnumberOfLayers: $numberOfLayers\nauthType: $authType")

        val manifest = fetchManifest(imageSpec, expectOciIndex = true, ref = imageSpec.ref)
        assert(numberOfLayers <= manifest.layers.size) { "Image contains ${manifest.layers.size} layers. Requested to extract $numberOfLayers layers." }

        downloadLayers(imageSpec, manifest.layers)
    }

    enum class MediaType(val typeString: String) {
        OCI_INDEX("application/vnd.oci.image.index.v1+json"),
        OCI_MANIFEST("application/vnd.oci.image.manifest.v1+json"),
        DOCKER_MANIFEST("application/vnd.docker.distribution.manifest.v2+json"),
    }

    private fun fetchManifest(imageSpec: ImageSpec, ref: String, expectOciIndex: Boolean): Manifest {
        echo("Getting image manifest for ${imageSpec.name}:$ref...")
        val manifestRequest = HttpRequest.newBuilder()
            .setHeader("Authorization", authorizationHeader)
            .setHeader("Accept", "${MediaType.OCI_INDEX.typeString},${MediaType.OCI_MANIFEST.typeString},${MediaType.DOCKER_MANIFEST.typeString}")
            .uri(HubRequest.MANIFESTS.getUrl(imageSpec, ref))
            .GET()
            .build()

        val manifestResponse = httpClient.send(manifestRequest, HttpResponse.BodyHandlers.ofString())
        val responseMediaType = manifestResponse.headers().firstValue("content-type").getOrElse {
            error("No 'content-type' header in response: ${manifestResponse.headers()}")
        }
        val manifestResponseBody = manifestResponse.body()

        return if (responseMediaType == MediaType.DOCKER_MANIFEST.typeString || responseMediaType == MediaType.OCI_MANIFEST.typeString) {
            echo("Received manifest. content-type=$responseMediaType")
            jsonMapper.readValue<Manifest>(manifestResponseBody)
        } else if (expectOciIndex && responseMediaType == MediaType.OCI_INDEX.typeString) {
            echo("Received OCI index. Looking for image manifest digest...")
            val index = jsonMapper.readValue<OciImageIndex>(manifestResponseBody)
            val manifestMetadata = index.manifests.firstOrNull { it.platform.toPlatformString() == platformString }
                ?: error("No image manifest found for specified platform ($platformString)")

            fetchManifest(imageSpec, expectOciIndex = false, ref = manifestMetadata.digest)
        } else {
            error("Unexpected media type in response: $responseMediaType")
        }
    }

    private fun downloadLayers(imageSpec: ImageSpec, layers: List<MediaMetadata>) {
        val outDirFile = outDir.toFile()
        outDirFile.mkdirs()

        for (i in 1..numberOfLayers) {
            val layer = layers[layers.size - i]
            echo("Fetching layer $i. Size: ${layer.size / (1024 * 1024)} MB. Digest: ${layer.digest}")

            val downloadLayerRequest = HttpRequest.newBuilder()
                .setHeader("Authorization", authorizationHeader)
                .setHeader("Accept", layer.mediaType)
                .uri(HubRequest.BLOBS.getUrl(imageSpec, layer.digest))
                .GET()
                .build()

            val gzipFile = File(outDirFile, "${imageSpec.name.substringAfter('/')}_layer$i.tar.gz")

            httpClient.send(downloadLayerRequest, HttpResponse.BodyHandlers.ofInputStream()).body().use { inStream ->
                FileOutputStream(gzipFile).use { outStream -> inStream.transferTo(outStream) }
            }

            echo("Extracting files from $gzipFile to $outDir")
            val exitCode = Runtime.getRuntime().exec("tar -xvzf $gzipFile -C $outDir").waitFor()
            check(exitCode == 0) { "Archive extraction failed. Filename $gzipFile. Exit code: $exitCode" }

            gzipFile.delete()
        }
    }

    private companion object {
        private const val DEFAULT_PLATFORM = "linux/amd64"
        private const val OUT_DIR = "./output"
        private val dockerImageSpecRegexp = Regex("^(?:(?=[^:/]{1,253})(?!-)[a-zA-Z0-9-]{1,63}(?<!-)(?:\\.(?!-)[a-zA-Z0-9-]{1,63}(?<!-))*(?::[0-9]{1,5})?/)?((?![._-])(?:[a-z0-9._-]*)(?<![._-])(?:/(?![._-])[a-z0-9._-]*(?<![._-]))*)(?::(?![.-])[a-zA-Z0-9_.-]{1,128})?\$")
    }
}

class ImageSpec(
    val domain: String,
    val name: String,
    val ref: String,
) {
    companion object {
        private const val DEFAULT_DOMAIN = "hub.docker.com"
        private const val DEFAULT_TAG = "latest"

        fun createFromString(imageSpecString: String): ImageSpec {
            val slashIndex = imageSpecString.indexOf('/')
            val colonIndex = imageSpecString.indexOf(':', slashIndex)

            val domain = if (slashIndex >= 0) imageSpecString.substring(0, slashIndex) else DEFAULT_DOMAIN
            val ref = if (colonIndex >= 0) imageSpecString.substring(colonIndex + 1) else DEFAULT_TAG

            val nameStartIdx = if (slashIndex >= 0) slashIndex + 1 else 0
            val nameEndIdx = if (colonIndex >= 0) colonIndex else imageSpecString.length

            val name = imageSpecString.substring(nameStartIdx, nameEndIdx)

            return ImageSpec(domain, name, ref)
        }
    }
}

enum class HubRequest(private val urlTemplate: String) {
    TAGS("https://%s/v2/repositories/%s/tags/%s"),
    MANIFESTS("https://%s/v2/%s/manifests/%s"),
    BLOBS("https://%s/v2/%s/blobs/%s");

    fun getUrl(imageSpec: ImageSpec, ref: String) = URI(urlTemplate.format(imageSpec.domain, imageSpec.name, ref))
}

class OciImageIndex(val mediaType: String, val manifests: List<ManifestMetadata>)

class ManifestMetadata(
    val mediaType: String,
    val digest: String,
    val size: Int,
    val annotations: Map<String, String> = emptyMap(),
    val platform: Platform
)

class Manifest(
    val schemaVersion: Int,
    val mediaType: String,
    val config: MediaMetadata,
    val layers: List<MediaMetadata>
)

data class Platform(val architecture: String, val os: String, val variant: String = "") {
    fun toPlatformString(): String = os + '/' + architecture + if (variant.isNotEmpty()) '/' else "" + variant
}

data class MediaMetadata(val mediaType: String, val digest: String, val size: Int)