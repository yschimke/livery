package ee.schimke.livery.docs.samples

import ee.schimke.livery.docs.quantityStepperInputs

fun quantityExample() {
  val example = quantityStepperInputs()
  println("${example.name}: ${example.quantity} × ${example.price}")
}
