package ee.schimke.livery.capture.cmp.samples

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteComposable
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.layout.RemoteText
import ee.schimke.composeai.rccreation.compose.modifier.RemoteModifier
import ee.schimke.composeai.rccreation.compose.modifier.background
import ee.schimke.composeai.rccreation.compose.modifier.clickable
import ee.schimke.composeai.rccreation.compose.modifier.clip
import ee.schimke.composeai.rccreation.compose.modifier.fillMaxWidth
import ee.schimke.composeai.rccreation.compose.modifier.height
import ee.schimke.composeai.rccreation.compose.modifier.padding
import ee.schimke.composeai.rccreation.compose.modifier.size
import ee.schimke.composeai.rccreation.compose.modifier.visibility
import ee.schimke.composeai.rccreation.compose.shapes.RemoteRoundedCornerShape
import ee.schimke.composeai.rccreation.compose.state.RemoteInt
import ee.schimke.composeai.rccreation.compose.state.rc
import ee.schimke.composeai.rccreation.compose.state.rdp
import ee.schimke.composeai.rccreation.compose.state.rs
import ee.schimke.composeai.rccreation.compose.state.rsp
import ee.schimke.livery.capture.cmp.LiveryValues

// The sample catalogs' Compose-authored designs, written once. This file is rc-players' Compose
// Multiplatform creation API; livery-capture's tests compile the same text against AndroidX's
// (generateAndroidxSamples rewrites the packages), which is how BindingAgreementTest authors one
// template through both bindings. Where the two APIs spell something differently, it goes through
// SampleCompat.kt, which each binding has its own copy of.

/**
 * The sample catalog's `banner-classic` in-app template, against `promo/banner@1`.
 */
@Composable
@RemoteComposable
fun BannerClassic(v: LiveryValues) {
  RemoteColumn(
    RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
    verticalArrangement = RemoteArrangement.spacedBy(8.rdp),
  ) {
    RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.SpaceBetween, verticalAlignment = RemoteAlignment.CenterVertically) {
      RemoteText(v.string("headline"), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 20.rsp, fontWeight = Bold)
      RemoteText("✕".rs, modifier = RemoteModifier.clickable(v.action("dismiss")).padding(4.rdp), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 18.rsp)
    }
    RemoteText(v.string("body"), color = v.color("theme.onSurface", Color(0xFF1D1B20)), fontSize = 14.rsp)
    RemoteBox(
      RemoteModifier.clip(RemoteRoundedCornerShape(20.rdp)).background(v.color("accent")).clickable(v.action("cta", mapOf("route" to "/premium"))).padding(horizontal = 20.rdp, vertical = 10.rdp),
    ) {
      RemoteText(v.string("cta"), color = Color.White.rc, fontSize = 14.rsp, fontWeight = Medium)
    }
  }
}

/**
 * The sample catalog's `poll-card` in-app template, against `social/poll@1`: up to four options,
 * each a row that votes when tapped, with a bar for its share of the votes. Everything that changes
 * as votes come in (shares, results, the mark on the chosen option, the summary) is a named value,
 * so new results are pushed into the document already on screen.
 */
@Composable
@RemoteComposable
fun PollCard(v: LiveryValues) {
  val contract = v.contract
  val onSurface = v.color("theme.onSurface", Color(0xFF1D1B20))
  val primary = v.color("theme.primary", Color(0xFF6750A4))
  val count = v.int("options.count")
  RemoteColumn(
    RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
    verticalArrangement = RemoteArrangement.spacedBy(10.rdp),
  ) {
    RemoteText(v.string("question"), color = onSurface, fontSize = 18.rsp, fontWeight = Bold)
    for (i in 0 until contract.lists.getValue("options").max) {
      // A row per option the data has; the rest are gone (Remote Compose's GONE is 0, VISIBLE 1).
      RemoteColumn(
        RemoteModifier.fillMaxWidth().visibility(count.isGreaterThan(RemoteInt(i)).toRemoteInt())
          .clip(RemoteRoundedCornerShape(12.rdp)).clickable(v.action("vote", mapOf("option" to "$i"))).padding(vertical = 4.rdp),
        verticalArrangement = RemoteArrangement.spacedBy(4.rdp),
      ) {
        RemoteRow(RemoteModifier.fillMaxWidth(), horizontalArrangement = RemoteArrangement.SpaceBetween, verticalAlignment = RemoteAlignment.CenterVertically) {
          RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically, horizontalArrangement = RemoteArrangement.spacedBy(6.rdp)) {
            RemoteText(v.string("options.$i.label"), color = onSurface, fontSize = 15.rsp)
            RemoteText(v.string("options.$i.mark"), color = primary, fontSize = 15.rsp, fontWeight = Bold)
          }
          RemoteText(v.string("options.$i.result"), color = onSurface, fontSize = 14.rsp)
        }
        // The track, and the share of it this option has.
        RemoteBox(RemoteModifier.fillMaxWidth().height(8.rdp).clip(RemoteRoundedCornerShape(4.rdp)).background(Color(0x1F79747E).rc)) {
          RemoteBox(RemoteModifier.fillMaxWidth(v.float("options.$i.share")).height(8.rdp).clip(RemoteRoundedCornerShape(4.rdp)).background(primary))
        }
      }
    }
    RemoteText(v.string("summary"), color = onSurface, fontSize = 13.rsp)
  }
}

/**
 * The two-way sample catalog's `quantity-stepper` in-app template, against `shop/quantity@1`: a two-way
 * control. Its `−` and `+` send `setQuantity` with the quantity the document computes when tapped
 * (`quantity - 1`, `quantity + 1`); the host writes it back, and the new value comes back in as the
 * `quantity` input. The document keeps no state of its own, so it always shows the host's value,
 * and a step past the contract's 0…9 is refused by the client, not by the design.
 */
@Composable
@RemoteComposable
fun QuantityStepper(v: LiveryValues) {
  val onSurface = v.color("theme.onSurface", Color(0xFF1D1B20))
  val primary = v.color("theme.primary", Color(0xFF6750A4))
  val quantity = v.int("quantity")
  RemoteRow(
    RemoteModifier.fillMaxWidth().clip(RemoteRoundedCornerShape(16.rdp)).background(v.color("theme.surface", Color(0xFFF7F2FA))).padding(16.rdp),
    horizontalArrangement = RemoteArrangement.SpaceBetween,
    verticalAlignment = RemoteAlignment.CenterVertically,
  ) {
    RemoteColumn(verticalArrangement = RemoteArrangement.spacedBy(2.rdp)) {
      RemoteText(v.string("name"), color = onSurface, fontSize = 16.rsp, fontWeight = Medium)
      RemoteText(v.string("price"), color = onSurface, fontSize = 13.rsp)
    }
    RemoteRow(verticalAlignment = RemoteAlignment.CenterVertically, horizontalArrangement = RemoteArrangement.spacedBy(12.rdp)) {
      RemoteBox(RemoteModifier.size(36.rdp).clip(RemoteRoundedCornerShape(18.rdp)).background(primary).clickable(v.set("setQuantity", quantity - 1)), contentAlignment = RemoteAlignment.Center) {
        RemoteText("−".rs, color = Color.White.rc, fontSize = 20.rsp, fontWeight = Bold)
      }
      RemoteText(quantity.toRemoteString(), color = onSurface, fontSize = 18.rsp, fontWeight = Bold)
      RemoteBox(RemoteModifier.size(36.rdp).clip(RemoteRoundedCornerShape(18.rdp)).background(primary).clickable(v.set("setQuantity", quantity + 1)), contentAlignment = RemoteAlignment.Center) {
        RemoteText("+".rs, color = Color.White.rc, fontSize = 20.rsp, fontWeight = Bold)
      }
    }
  }
}
