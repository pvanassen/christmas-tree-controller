package nl.pvanassen.christmas.tree.controller.service

import io.micronaut.context.annotation.Context
import io.micronaut.context.annotation.Property
import io.micronaut.scheduling.annotation.Scheduled
import io.reactivex.schedulers.Schedulers
import nl.pvanassen.christmas.tree.controller.client.RemoteAnimationClient
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.locks.ReentrantLock
import javax.annotation.PostConstruct
import kotlin.concurrent.withLock

@Context
class RemoteAnimationService(private val remoteAnimationClient: RemoteAnimationClient,
                             private val consulChristmasTreeService: ConsulChristmasTreeService,
                             @Property(name = "app.debug") private val debug:Boolean) {

    private val lock = ReentrantLock()

    private val logger = LoggerFactory.getLogger(this.javaClass)

    private val normalAnimationNameUrl: MutableMap<String, String> = HashMap()

    private val specialAnimationNameUrl: MutableMap<String, String> = HashMap()

    private val random = Random()

    private var lastAnimation = "First!"

    @Scheduled(fixedRate = "1m")
    @PostConstruct
    fun discoverAnimations() {
        val stage = if (debug) {
            "debug"
        }
        else {
            "production"
        }
        logger.info("Refreshing animations")

        val services =
                try {
                    consulChristmasTreeService.getChristmasTreeServices()
                            .filter { it.first.contains("animation") }
                            .filter { it.first.contains(stage) }
                            .map { Pair(it.first.filter { value -> value != "christmas-tree" }, it.second) }
                            .map { Pair(it.first.filter { value -> value != "animation" }, it.second) }
                            .map { Pair(it.first.filter { value -> value != stage }, it.second) }
                }
                catch (e: Exception) {
                    logger.error("Cannot connect to Consul", e)
                    LinkedList<Pair<List<String?>, String>>()
                }

        val normalAnimations = services
                .filter { it.first.size == 1 }
                .map { Pair(it.first[0] ?: "unknown", it.second) }
                .toMap()

        val specialAnimations = services
                .filter { it.first.contains("special") }
                .map { Pair(it.first.filter { value -> value != "special" }, it.second) }
                .map { Pair(it.first[0] ?: "unknown", it.second) }
                .toMap()

        lock.withLock {
            normalAnimationNameUrl.clear()
            specialAnimationNameUrl.clear()
            if (debug) {
                normalAnimationNameUrl["local-debug"] = "http://localhost:8081"
                specialAnimationNameUrl["local-debug"] = "http://localhost:8081"
            }
            else {
                normalAnimationNameUrl.putAll(normalAnimations)
                specialAnimationNameUrl.putAll(specialAnimations)
            }
        }
    }

    fun getFramesFromRandomAnimation(seconds:Int, fps:Int, callback: (ByteArray) -> Unit) {
        // [random.nextInt(normalAnimationClients.size)]
        val animationName = normalAnimationNameUrl.keys.toList()[random.nextInt(normalAnimationNameUrl.size)]
        lastAnimation = animationName
        logger.info("Using $animationName")
        remoteAnimationClient.getAnimation(normalAnimationNameUrl[animationName]!!, seconds, fps)
                .onErrorReturn {
                    logger.error("Error while fetching $animationName", it)
                    discoverAnimations()
                    ByteArray(0)
                }
                .retry(3)
                .map { callback(it) }
                .subscribeOn(Schedulers.io())
                .subscribe()
    }

    fun getFramesFromFireworks(fps:Int, callback: (ByteArray) -> Unit) {
        if (debug) {
            getSpecialAnimation("local-debug", fps, callback)
        }
        else {
            getSpecialAnimation("fireworks", fps, callback)
        }
    }

    fun getSunriseAnimation(fps:Int, callback: (ByteArray) -> Unit) {
        if (debug) {
            getSpecialAnimation("local-debug", fps, callback)
        }
        else {
            getSpecialAnimation("sunrise", fps, callback)
        }
    }

    fun getSunsetAnimation(fps:Int, callback: (ByteArray) -> Unit) {
        if (debug) {
            getSpecialAnimation("local-debug", fps, callback)
        }
        else {
            getSpecialAnimation("sunset", fps, callback)
        }
    }

    private fun getSpecialAnimation(animationName:String, fps:Int, callback: (ByteArray) -> Unit) {
        remoteAnimationClient.getAnimation(specialAnimationNameUrl[animationName]!!, -1, fps)
                .onErrorReturn {
                    logger.error("Error while fetching $animationName", it)
                    discoverAnimations()
                    ByteArray(0)
                }
                .retry(3)
                .map { callback(it) }
                .subscribeOn(Schedulers.io())
                .subscribe()
    }
}