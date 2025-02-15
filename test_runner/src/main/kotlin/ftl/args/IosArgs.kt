package ftl.args

import ftl.args.ArgsHelper.assertCommonProps
import ftl.args.ArgsHelper.assertFileExists
import ftl.args.ArgsHelper.assertGcsFileExists
import ftl.args.ArgsHelper.createGcsBucket
import ftl.args.ArgsHelper.createJunitBucket
import ftl.args.ArgsHelper.evaluateFilePath
import ftl.args.ArgsHelper.mergeYmlMaps
import ftl.args.ArgsHelper.yamlMapper
import ftl.args.ArgsToString.devicesToString
import ftl.args.ArgsToString.listToString
import ftl.args.yml.FlankYml
import ftl.args.yml.GcloudYml
import ftl.args.yml.IosFlankYml
import ftl.args.yml.IosGcloudYml
import ftl.args.yml.IosGcloudYmlParams
import ftl.args.yml.YamlDeprecated
import ftl.cli.firebase.test.ios.IosRunCommand
import ftl.config.Device
import ftl.config.FtlConstants
import ftl.ios.IosCatalog
import ftl.ios.Xctestrun
import ftl.util.Utils.fatalError
import java.nio.file.Files
import java.nio.file.Path

class IosArgs(
    gcloudYml: GcloudYml,
    iosGcloudYml: IosGcloudYml,
    flankYml: FlankYml,
    iosFlankYml: IosFlankYml,
    override val data: String,
    val cli: IosRunCommand? = null
) : IArgs {

    private val gcloud = gcloudYml.gcloud
    override val resultsBucket: String
    override val resultsDir = cli?.resultsDir ?: gcloud.resultsDir
    override val recordVideo = cli?.recordVideo ?: cli?.noRecordVideo?.not() ?: gcloud.recordVideo
    override val testTimeout = cli?.timeout ?: gcloud.timeout
    override val async = cli?.async ?: gcloud.async
    override val resultsHistoryName = cli?.resultsHistoryName ?: gcloud.resultsHistoryName
    override val flakyTestAttempts = cli?.flakyTestAttempts ?: gcloud.flakyTestAttempts

    private val iosGcloud = iosGcloudYml.gcloud
    var xctestrunZip = cli?.test ?: iosGcloud.test ?: fatalError("test is not set")
    var xctestrunFile = cli?.xctestrunFile ?: iosGcloud.xctestrunFile ?: fatalError("xctestrun-file is not set")
    val xcodeVersion = cli?.xcodeVersion ?: iosGcloud.xcodeVersion
    val devices = cli?.device ?: iosGcloud.device

    private val flank = flankYml.flank
    override val maxTestShards = cli?.maxTestShards ?: flank.maxTestShards
    override val shardTime = cli?.shardTime ?: flank.shardTime
    override val repeatTests = cli?.repeatTests ?: flank.repeatTests
    override val smartFlankGcsPath = cli?.smartFlankGcsPath ?: flank.smartFlankGcsPath
    override val smartFlankDisableUpload = cli?.smartFlankDisableUpload ?: flank.smartFlankDisableUpload
    override val testTargetsAlwaysRun = cli?.testTargetsAlwaysRun ?: flank.testTargetsAlwaysRun
    override val filesToDownload = cli?.filesToDownload ?: flank.filesToDownload
    override val disableSharding = cli?.disableSharding ?: flank.disableSharding
    override val project = cli?.project ?: flank.project
    override val localResultDir = cli?.localResultsDir ?: flank.localResultDir

    private val iosFlank = iosFlankYml.flank
    val testTargets = cli?.testTargets ?: iosFlank.testTargets.filterNotNull()

    // computed properties not specified in yaml
    val testShardChunks: List<List<String>> by lazy {
        if (disableSharding) return@lazy listOf(emptyList<String>())

        val validTestMethods = Xctestrun.findTestNames(xctestrunFile)
        val testsToShard = filterTests(validTestMethods, testTargets).distinct()

        ArgsHelper.calculateShards(testsToShard, this)
    }

    init {
        resultsBucket = createGcsBucket(project, cli?.resultsBucket ?: gcloud.resultsBucket)
        createJunitBucket(project, flank.smartFlankGcsPath)

        if (xctestrunZip.startsWith(FtlConstants.GCS_PREFIX)) {
            assertGcsFileExists(xctestrunZip)
        } else {
            xctestrunZip = evaluateFilePath(xctestrunZip)
            assertFileExists(xctestrunZip, "xctestrunZip")
        }
        xctestrunFile = evaluateFilePath(xctestrunFile)
        assertFileExists(xctestrunFile, "xctestrunFile")

        devices.forEach { device -> assertDeviceSupported(device) }
        assertXcodeSupported(xcodeVersion)

        assertCommonProps(this)
    }

    private fun assertXcodeSupported(xcodeVersion: String?) {
        if (xcodeVersion == null) return
        if (!IosCatalog.supportedXcode(xcodeVersion, this.project)) {
            fatalError(("Xcode $xcodeVersion is not a supported Xcode version"))
        }
    }

    private fun assertDeviceSupported(device: Device) {
        if (!IosCatalog.supportedDevice(device.model, device.version, this.project)) {
            fatalError("iOS ${device.version} on ${device.model} is not a supported device")
        }
    }

    override fun toString(): String {
        return """
IosArgs
    gcloud:
      results-bucket: $resultsBucket
      results-dir: $resultsDir
      record-video: $recordVideo
      timeout: $testTimeout
      async: $async
      results-history-name: $resultsHistoryName
      # iOS gcloud
      test: $xctestrunZip
      xctestrun-file: $xctestrunFile
      xcode-version: $xcodeVersion
      device:
${devicesToString(devices)}
      flaky-test-attempts: $flakyTestAttempts

    flank:
      max-test-shards: $maxTestShards
      shard-time: $shardTime
      repeat-tests: $repeatTests
      smart-flank-gcs-path: $smartFlankGcsPath
      smart-flank-disable-upload: $smartFlankDisableUpload
      test-targets-always-run:
${listToString(testTargetsAlwaysRun)}
      files-to-download:
${listToString(filesToDownload)}
      # iOS flank
      test-targets:
${listToString(testTargets)}
      disable-sharding: $disableSharding
      project: $project
      local-result-dir: $localResultDir
    """.trimIndent()
    }

    companion object : IArgsCompanion {
        override val validArgs by lazy {
            mergeYmlMaps(GcloudYml, IosGcloudYml, FlankYml, IosFlankYml)
        }

        fun load(data: Path, cli: IosRunCommand? = null): IosArgs = load(String(Files.readAllBytes(data)), cli)

        fun load(yamlData: String, cli: IosRunCommand? = null): IosArgs {
            val data = YamlDeprecated.modifyAndThrow(yamlData, android = false)

            val flankYml = yamlMapper.readValue(data, FlankYml::class.java)
            val iosFlankYml = yamlMapper.readValue(data, IosFlankYml::class.java)
            val gcloudYml = yamlMapper.readValue(data, GcloudYml::class.java)
            val iosGcloudYml = yamlMapper.readValue(data, IosGcloudYml::class.java)

            return IosArgs(
                gcloudYml,
                iosGcloudYml,
                flankYml,
                iosFlankYml,
                data,
                cli
            )
        }

        fun default(): IosArgs {
            return IosArgs(
                GcloudYml(),
                IosGcloudYml(IosGcloudYmlParams(test = ".", xctestrunFile = ".")),
                FlankYml(),
                IosFlankYml(),
                "",
                IosRunCommand())
        }
    }
}

fun filterTests(validTestMethods: List<String>, testTargetsRgx: List<String?>): List<String> {
    if (testTargetsRgx.isEmpty()) {
        return validTestMethods
    }

    return validTestMethods.filter { test ->
        testTargetsRgx.filterNotNull().forEach { target ->
            try {
                if (test.matches(target.toRegex())) {
                    return@filter true
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid regex: $target", e)
            }
        }

        return@filter false
    }
}
