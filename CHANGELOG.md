# Changelog

## [0.5.0](https://github.com/yschimke/livery/compare/v0.4.0...v0.5.0) (2026-11-07)


### Features

* @livery/a2ui, Livery components in A2UI's web renderer, over livery-web (Wasm) ([#31](https://github.com/yschimke/livery/issues/31)) ([9b5c1e6](https://github.com/yschimke/livery/commit/9b5c1e6b84d5b2148f01e05d1b450ef4f6d7d1b2))
* livery-a2ui, the contract-to-A2UI projection, pinned by conformance/a2ui.json ([#26](https://github.com/yschimke/livery/issues/26)) ([1b8fc17](https://github.com/yschimke/livery/commit/1b8fc1727edf95c48cf22a2b7eae48814d43779d))
* livery-catalog, the catalog build and checks in common Kotlin ([#27](https://github.com/yschimke/livery/issues/27)) ([4101000](https://github.com/yschimke/livery/commit/4101000afcd788c9f6b235fe477eabab28b7f241))
* livery-mcp-core, the agent tools as a common, transport-free MCP server ([#29](https://github.com/yschimke/livery/issues/29)) ([5f65477](https://github.com/yschimke/livery/commit/5f65477c8e11437059c09b96a286ee106c71fbcf))
* suspend MCP calls, and livery-uibuilder in common Kotlin ([#30](https://github.com/yschimke/livery/issues/30)) ([a5c4e4d](https://github.com/yschimke/livery/commit/a5c4e4d8a39e47c65ed808c1cf645f4473f5457f))
* validate_a2ui and a2ui_component_schema, and an A2UI message validator for agents ([#32](https://github.com/yschimke/livery/issues/32)) ([f492e15](https://github.com/yschimke/livery/commit/f492e1512b804afd2fca06e0f317c91b83f8e0e9))


### Bug Fixes

* a malformed A2UI signature is refused, and a replaced document never plays on ([#24](https://github.com/yschimke/livery/issues/24)) ([dc9c240](https://github.com/yschimke/livery/commit/dc9c240f9ca378efead8d28444b60519cdad8fb5))

## [0.4.0](https://github.com/yschimke/livery/compare/v0.3.0...v0.4.0) (2026-11-07)


### Features

* concise review comments with before/after pictures, kept preview data, and fail on Android 16 widget crashes ([#19](https://github.com/yschimke/livery/issues/19)) ([816f8b5](https://github.com/yschimke/livery/commit/816f8b5c5d93505002fbe4a92b798f11e8a6c5b4))
* Livery contracts as A2UI components, set actions for two-way binding, and a livery-a2ui-compose prototype ([#22](https://github.com/yschimke/livery/issues/22)) ([704712d](https://github.com/yschimke/livery/commit/704712dcff14b7741873123ef798be8856d66acc))
* livery play, a template played as this release of Livery plays it ([#20](https://github.com/yschimke/livery/issues/20)) ([7db8cc5](https://github.com/yschimke/livery/commit/7db8cc5efc8eb4d1a058a0ff2d445f75255f6011))
* risky features for documents: template signatures, an agent-bytes policy, and catalog gating (protocol level 8) ([#23](https://github.com/yschimke/livery/issues/23)) ([7500f34](https://github.com/yschimke/livery/commit/7500f349ae043107cb093c15f63ab6278a1eeb9b))

## [0.3.0](https://github.com/yschimke/livery/compare/v0.2.0...v0.3.0) (2026-11-06)


### Features

* designs that aren't automatic, so a season's design draws only in its season ([#18](https://github.com/yschimke/livery/issues/18)) ([9182b9c](https://github.com/yschimke/livery/commit/9182b9cf01bd540868f3b9560e7c4b2748efd8af))
* repository mode: seasons, a review report for pull requests, and reusable catalog workflows ([#17](https://github.com/yschimke/livery/issues/17)) ([2849894](https://github.com/yschimke/livery/commit/28498945d2434beb64d7a6ffef4d6f706fc2555c))
* secondary screens: interstitials, dialogs, sheets and full screens, one level deep ([#15](https://github.com/yschimke/livery/issues/15)) ([1223f5b](https://github.com/yschimke/livery/commit/1223f5b5ba68c55855efc0fe09c60a157d6d94d1))

## [0.2.0](https://github.com/yschimke/livery/compare/v0.1.1...v0.2.0) (2026-11-06)


### Features

* contract minors, template compatibility checks and livery check ([#11](https://github.com/yschimke/livery/issues/11)) ([2b2c744](https://github.com/yschimke/livery/commit/2b2c744400aa5605fe06eb6e86a26c12cbe0f6fd))

## 0.1.1 (2026-11-05)


### Features

* agent tools, previews, template history, UI Builder source, Maven Central publishing and baked catalogs ([#5](https://github.com/yschimke/livery/issues/5)) ([3892d3d](https://github.com/yschimke/livery/commit/3892d3dbf63cff53fa7a752e15215849ab06aae2))
* Compose Multiplatform and Swift clients, resources, and the protocol spec with signing ([#2](https://github.com/yschimke/livery/issues/2)) ([a80e479](https://github.com/yschimke/livery/commit/a80e47975378410608d0bfc49a7dd1958b4b7a96))
* launcher and Wear widgets, and a documentation site ([#4](https://github.com/yschimke/livery/issues/4)) ([2c7ce0b](https://github.com/yschimke/livery/commit/2c7ce0b8c0bfa212e9693c44a46af0abd4d2284b))
* livery-instantiate, writing a slot's values into templates for widget hosts ([#3](https://github.com/yschimke/livery/issues/3)) ([b02a9f4](https://github.com/yschimke/livery/commit/b02a9f49f873939bf3100f2adfc3a2c51fb42ce3))
* notification bodies from Livery templates, and release-please releases ([#7](https://github.com/yschimke/livery/issues/7)) ([fd58d67](https://github.com/yschimke/livery/commit/fd58d6754a46ffeed58d005acb6d3be66063b503))
* protocol, client core, publisher CLI and sample catalog ([#1](https://github.com/yschimke/livery/issues/1)) ([b89edce](https://github.com/yschimke/livery/commit/b89edce70acf310aef183028edb448f8049f0300))


### Bug Fixes

* publish under ee.schimke.composeai, the namespace Maven Central verified ([#6](https://github.com/yschimke/livery/issues/6)) ([b97c047](https://github.com/yschimke/livery/commit/b97c0470c74f6c31700c80c23e9491158a84fda2))
