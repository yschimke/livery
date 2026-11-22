package ee.schimke.livery.uibuilder.capture

import ee.schimke.composeai.rccreation.compose.layout.RemoteAlignment
import ee.schimke.composeai.rccreation.compose.layout.RemoteArrangement
import ee.schimke.composeai.rccreation.compose.layout.RemoteBox
import ee.schimke.composeai.rccreation.compose.layout.RemoteColumn
import ee.schimke.composeai.rccreation.compose.layout.RemoteRow
import ee.schimke.composeai.rccreation.compose.state.rdp

/**
 * The UI Builder frame's own layouts, which every catalog's designs use: `layout/column`,
 * `layout/row` and `layout/box`, and the collapsible column and row, drawn as plain ones (content
 * that doesn't fit is clipped, not dropped). A catalog's components may replace any of them.
 *
 * Properties: `verticalArrangement` / `horizontalArrangement` (`top`/`start`, `center`,
 * `bottom`/`end`, `spaceBetween`, `spaceAround`, `spaceEvenly`, else spaced by
 * `verticalSpacingDp` / `horizontalSpacingDp`), `horizontalAlignment` (`start`, `center`, `end`),
 * `verticalAlignment` (`top`, `center`, the default, `bottom`) and a box's `contentAlignment`
 * (`topStart`, `center`, …).
 *
 * Each is drawn once, as a literal: Remote Compose has no arrangement or alignment that follows a
 * value. A design whose layout property is bound to a host input fails [UiDesign.validate] rather
 * than capturing its preview value; a design's own (`local`) state there draws its initial value.
 */
object UiLayouts {
  private fun vertical(node: UiNode) =
    when (node.string("verticalArrangement")) {
      "center" -> RemoteArrangement.Center
      "bottom" -> RemoteArrangement.Bottom
      "spaceBetween" -> RemoteArrangement.SpaceBetween
      "spaceAround" -> RemoteArrangement.SpaceAround
      "spaceEvenly" -> RemoteArrangement.SpaceEvenly
      else -> RemoteArrangement.spacedBy(node.float("verticalSpacingDp").rdp)
    }

  private fun horizontal(node: UiNode) =
    when (node.string("horizontalArrangement")) {
      "center" -> RemoteArrangement.Center
      "end" -> RemoteArrangement.End
      "spaceBetween" -> RemoteArrangement.SpaceBetween
      "spaceAround" -> RemoteArrangement.SpaceAround
      "spaceEvenly" -> RemoteArrangement.SpaceEvenly
      else -> RemoteArrangement.spacedBy(node.float("horizontalSpacingDp").rdp)
    }

  private val column: UiComponent = { node, modifier ->
    RemoteColumn(
      modifier = modifier,
      verticalArrangement = vertical(node),
      horizontalAlignment =
        when (node.string("horizontalAlignment")) {
          "center" -> RemoteAlignment.CenterHorizontally
          "end" -> RemoteAlignment.End
          else -> RemoteAlignment.Start
        },
    ) {
      node.Children()
    }
  }

  private val row: UiComponent = { node, modifier ->
    RemoteRow(
      modifier = modifier,
      horizontalArrangement = horizontal(node),
      verticalAlignment =
        when (node.string("verticalAlignment")) {
          "top" -> RemoteAlignment.Top
          "bottom" -> RemoteAlignment.Bottom
          else -> RemoteAlignment.CenterVertically
        },
    ) {
      node.Children()
    }
  }

  private val box: UiComponent = { node, modifier ->
    RemoteBox(
      modifier = modifier,
      contentAlignment =
        when (node.string("contentAlignment")) {
          "topCenter" -> RemoteAlignment.TopCenter
          "topEnd" -> RemoteAlignment.TopEnd
          "centerStart" -> RemoteAlignment.CenterStart
          "center" -> RemoteAlignment.Center
          "centerEnd" -> RemoteAlignment.CenterEnd
          "bottomStart" -> RemoteAlignment.BottomStart
          "bottomCenter" -> RemoteAlignment.BottomCenter
          "bottomEnd" -> RemoteAlignment.BottomEnd
          else -> RemoteAlignment.TopStart
        },
    ) {
      node.Children()
    }
  }

  val components: Map<String, UiComponent> =
    mapOf(
      "layout/column" to column,
      "layout/collapsible-column" to column,
      "layout/row" to row,
      "layout/collapsible-row" to row,
      "layout/box" to box,
    )
}
