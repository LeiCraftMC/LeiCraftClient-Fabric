package de.leicraftmc.leicraftclient.client.update

import de.leicraftmc.leicraftclient.client.api.ApiModels
import net.fabricmc.loader.api.SemanticVersion
import net.fabricmc.loader.api.VersionParsingException

/**
 * Which release, if any, to offer as an update.
 *
 * Versions compare as SemVer, with Fabric Loader's parser, so pre-release
 * numbers compare as numbers: `2.0.0-pre.2` < `2.0.0-pre.10` < `2.0.0`. On a
 * stable version only stable releases are offered; on a pre-release, anything
 * newer is. Versions that do not parse (a `dev` build, an odd tag) are never
 * offered and never offer anything.
 *
 * Pure, so it is unit-tested on a plain JVM.
 */
object ReleaseSelector {

    /** The newest of [releases] worth offering to someone running [current], or null. */
    fun newer(current: String, releases: List<ApiModels.Release>): ApiModels.Release? {
        val installed = parse(current) ?: return null
        val takesPreReleases = installed.prereleaseKey.isPresent
        return releases
            .mapNotNull { release -> parse(release.version)?.let { release to it } }
            .filter { (_, version) -> version > installed && (takesPreReleases || version.prereleaseKey.isEmpty) }
            .maxByOrNull { (_, version) -> version }
            ?.first
    }

    private fun parse(version: String): SemanticVersion? = try {
        SemanticVersion.parse(version)
    } catch (e: VersionParsingException) {
        null
    }
}
