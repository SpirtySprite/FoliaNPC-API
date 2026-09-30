# Changelog

All notable changes to FoliaNPC are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Fixed
- Players riding a vehicle now keep their tracked position up to date (via `VehicleMoveEvent`), so NPCs
  spawn and despawn for them while they ride. Movement of a rider is not reported as player movement.
- Skin lookups that fail (Mojang outage or rate limit) are remembered for 5 seconds instead of being
  retried on every request. Configure with `FoliaNpc#skinFailureCooldown`; `Duration.ZERO` disables it.
- `fetchSkinFromUrl` results are cached per URL and concurrent requests for one URL share a single call.

### Added
- Continuous integration with a Paper API version matrix, CodeQL analysis and Dependabot.
- Release workflow: pushing a `vX.Y.Z` tag builds the project and publishes a GitHub release.
- `CONTRIBUTING.md`, `SECURITY.md`, issue and pull request templates, and `.editorconfig`.
- Sources and javadoc jars are built with every `mvn verify`.

## [1.1.1]

Initial tracked release. See the git history for earlier changes.
