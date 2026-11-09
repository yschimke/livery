package ee.schimke.livery.docs

/**
 * Example inputs for the published quantity-stepper Remote Compose document.
 *
 * This documentation sample models its data; the live preview plays the captured RC
 * document rather than compiling this class or a copy of the component.
 *
 * @property name Product name displayed by the component.
 * @property price Formatted price displayed by the component.
 * @property quantity Initial quantity. Each preview keeps subsequent changes locally.
 */
data class QuantityInputs(val name: String, val price: String, val quantity: Int)

/**
 * Returns the public example inputs for the quantity-stepper component.
 *
 * [Try the interactive component](https://preview.coo.ee/p/quantity-stepper/r/fixture-1/)
 *
 * [Try a second independent instance](https://preview.coo.ee/p/quantity-stepper/r/fixture-1/)
 *
 * The links remain ordinary links in IDE KDoc and Markdown. The optional HTML adapter
 * adds revision-pinned embeds to generated Dokka pages. These fixture URLs are examples
 * until an operator installs that publication on the host.
 *
 * @sample ee.schimke.livery.docs.samples.quantityExample
 * @return Public example data; it contains no account or shared application state.
 */
fun quantityStepperInputs(): QuantityInputs = QuantityInputs("Oat milk, 1 l", "€2.49", 1)
