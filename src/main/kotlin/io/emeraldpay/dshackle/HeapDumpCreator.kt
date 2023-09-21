package io.emeraldpay.dshackle

import com.sun.management.HotSpotDiagnosticMXBean
import org.slf4j.LoggerFactory
import org.springframework.util.ResourceUtils
import java.io.File
import java.io.FileNotFoundException
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.BasicFileAttributes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class HeapDumpCreator {
    companion object {
        private const val MAX_DUMPS = 3

        private val log = LoggerFactory.getLogger(HeapDumpCreator::class.java)
        private val executorService = Executors.newSingleThreadScheduledExecutor()

        fun init() {
            val envVars = System.getenv()
            val enableCreateDumps = envVars["HEAP_DUMP_ENABLE"]?.toBoolean() ?: true
            val dumpsPath = envVars["HEAP_DUMP_PATH"] ?: "/etc/dshackle/dumps"
            val dumpsPathExists = heapDumpsPathExists(dumpsPath)
                .also {
                    if (!it) {
                        log.warn("$dumpsPath does not exist")
                    }
                }

            if (enableCreateDumps && dumpsPathExists) {
                executorService.scheduleAtFixedRate({ dumpCheckTask(dumpsPath) }, 0, 30, TimeUnit.MINUTES)

                configHeapDump(dumpsPath)
            } else {
                log.warn("Heap dump creation is turned off")
            }
        }

        private fun configHeapDump(dumpsPath: String) {
            val date = SimpleDateFormat("yyyyMMddHHmmss").format(Date())
            val fileName = "$dumpsPath/heap_$date.hprof"

            val bean = ManagementFactory.newPlatformMXBeanProxy(
                ManagementFactory.getPlatformMBeanServer(),
                "com.sun.management:type=HotSpotDiagnostic",
                HotSpotDiagnosticMXBean::class.java
            )
            bean.setVMOption("HeapDumpOnOutOfMemoryError", "true")
            bean.setVMOption("HeapDumpPath", fileName)
        }

        private fun heapDumpsPathExists(dumpsPath: String): Boolean {
            return try {
                ResourceUtils.getFile(dumpsPath).exists()
            } catch (e: FileNotFoundException) {
                false
            }
        }

        private fun dumpCheckTask(dumpsPath: String) {
            val files = File(dumpsPath).listFiles()
                ?.filter { it.name.endsWith("hprof") }
                ?: emptyList()

            if (files.isNotEmpty()) {
                log.warn("There are some heap dumps, please send them to dshackle developers")
            }
            if (files.size >= MAX_DUMPS) {
                files.minBy {
                    Files.readAttributes(Paths.get(it.absolutePath), BasicFileAttributes::class.java).creationTime()
                }.run {
                    this.delete()
                }
            }
        }
    }
}
