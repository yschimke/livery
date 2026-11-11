# Changelog

## [0.10.0](https://github.com/yschimke/livery/compare/v0.9.0...v0.10.0) (2026-11-11)


### Features

* **capture:** capture with rc-players' Compose Multiplatform creation API, beside AndroidX's ([#64](https://github.com/yschimke/livery/issues/64)) ([ebdbda2](https://github.com/yschimke/livery/commit/ebdbda2e0ccdd6b41a3e53117683ab00e6d00d1a))
* **gradle:** preview every template in a catalog with renderLiveryPreviews ([#60](https://github.com/yschimke/livery/issues/60)) ([b33df86](https://github.com/yschimke/livery/commit/b33df868fa508d61410a399def63a99b2e7c4f04))
* **render:** also export the preview matrix in compose-preview's shared shape ([#63](https://github.com/yschimke/livery/issues/63)) ([7890f9e](https://github.com/yschimke/livery/commit/7890f9eb2f05f914f862998d6b771b4adc116d76))
* **render:** export the preview matrix as cases any renderer can play ([#62](https://github.com/yschimke/livery/issues/62)) ([30bc87d](https://github.com/yschimke/livery/commit/30bc87d07a9a8d06d9b03d949d861651572c6698))

## [0.9.0](https://github.com/yschimke/livery/compare/v0.8.0...v0.9.0) (2026-11-10)


### Features

* **gradle:** capture a catalog's templates from Kotlin at build time ([#59](https://github.com/yschimke/livery/issues/59)) ([c428c99](https://github.com/yschimke/livery/commit/c428c991c416da8812d46d4a9c225fb2c37810d0))


### Bug Fixes

* draw host-density templates on the platform's widget player ([#57](https://github.com/yschimke/livery/issues/57)) ([b7dd0f2](https://github.com/yschimke/livery/commit/b7dd0f2951041293abc700284747ccb1b026c110))
* **notifications:** wrap notification bodies so Android 17 can nest them ([#53](https://github.com/yschimke/livery/issues/53)) ([226e4fc](https://github.com/yschimke/livery/commit/226e4fc0ccaba49f7a9d96427bc187ee33024605))
* **npm:** publish Livery packages under the yschimke scope ([#58](https://github.com/yschimke/livery/issues/58)) ([3635537](https://github.com/yschimke/livery/commit/3635537626ebdfa2cda7d4566365d0faa9a5be71))

## [0.8.0](https://github.com/yschimke/livery/compare/v0.7.0...v0.8.0) (2026-11-09)


### Features

* **build:** livery-bom, and publish only the modules a release changed ([#51](https://github.com/yschimke/livery/issues/51)) ([14761c1](https://github.com/yschimke/livery/commit/14761c1df9886de0463bd7471b9bea9ab5b951e6))
* **compose:** draw every card on its first frame, splash-screen startup hook, rc-players 2.30 ([#47](https://github.com/yschimke/livery/issues/47)) ([1cab479](https://github.com/yschimke/livery/commit/1cab479d14fb190fd420b1aa2ec85ce8256f5a48))
* **compose:** share one prepared document between every card of a template ([#50](https://github.com/yschimke/livery/issues/50)) ([9b31d7a](https://github.com/yschimke/livery/commit/9b31d7aa98fac968a14eccaae8f30853504a5afe))
* distribute shareable component previews and Dokka embeds ([#48](https://github.com/yschimke/livery/issues/48)) ([db1222f](https://github.com/yschimke/livery/commit/db1222f7fc0ddb146b576956bb5d9de6a59802b2))
* **server:** extract reusable component publication hosting ([#52](https://github.com/yschimke/livery/issues/52)) ([e0ed587](https://github.com/yschimke/livery/commit/e0ed587800f0a92d4355d936f7818b13e594f971))

## [0.7.0](https://github.com/yschimke/livery/compare/v0.6.0...v0.7.0) (2026-11-09)


### Features

* **a2ui:** support contract queries and Compose template forms ([#43](https://github.com/yschimke/livery/issues/43)) ([10cef3a](https://github.com/yschimke/livery/commit/10cef3a6ae6c2958d64d4530a6e05bd804557711))
* **client:** plan a slot from queries, most specific first ([#45](https://github.com/yschimke/livery/issues/45)) ([d3cad67](https://github.com/yschimke/livery/commit/d3cad670c946f933d2ebd24a3da576422faa0634))
* **web:** add an in-page Compose Multiplatform player ([#41](https://github.com/yschimke/livery/issues/41)) ([922ca08](https://github.com/yschimke/livery/commit/922ca0879cc92b6724b082ea0d4bbc1326caec02))
* **web:** add renderer adapters caching optimized Wasm and measured height ([#42](https://github.com/yschimke/livery/issues/42)) ([9578d39](https://github.com/yschimke/livery/commit/9578d3952faa18df1e136220b9db313007bf01a4))
* **web:** add standalone Remote Compose component previews ([#46](https://github.com/yschimke/livery/issues/46)) ([de03ef5](https://github.com/yschimke/livery/commit/de03ef56801d4d1a0d6c4bbb05a614193addf02e))
* **web:** an A2UI template named by sha256, from a trusted catalog or signed by url ([#39](https://github.com/yschimke/livery/issues/39)) ([743b22d](https://github.com/yschimke/livery/commit/743b22d37d2276ee96fd099ae63166a80cf44569))


### Bug Fixes

* preserve catalog retry and trust guarantees during refresh ([#44](https://github.com/yschimke/livery/issues/44)) ([676d988](https://github.com/yschimke/livery/commit/676d9886b879a694ec909c7a49d1f69eb5f6cf53))

## [0.6.0](https://github.com/yschimke/livery/compare/v0.5.0...v0.6.0) (2026-11-08)


### Features

* livery check notes a template that gains a risky feature ([#34](https://github.com/yschimke/livery/issues/34)) ([8eb0d02](https://github.com/yschimke/livery/commit/8eb0d02da2980a16e80aa991574a7a353ecd6963))
* **web:** a surface's createSurface primaryColor colours its Livery templates ([#37](https://github.com/yschimke/livery/issues/37)) ([8423978](https://github.com/yschimke/livery/commit/8423978bb17f0ed7c37529a3b457e79be0b5da79))
* **web:** an A2UI component the page gives no height takes its template's ([#38](https://github.com/yschimke/livery/issues/38)) ([9d5f783](https://github.com/yschimke/livery/commit/9d5f783e3940e35df603619718bad25553422140))
* **web:** events from an A2UI component carry its a2ui:&lt;surface&gt;/&lt;component&gt; decision id ([#36](https://github.com/yschimke/livery/issues/36)) ([af32e4c](https://github.com/yschimke/livery/commit/af32e4c729bc1c799660842cdb6509414cd447a8))

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
