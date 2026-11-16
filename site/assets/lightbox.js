// Opens an image of a page in a modal, larger than the page's column: a click on it (or on the link around it to the image
// file), or Enter or Space on it. Esc, a click outside the image, or the close button closes it. Without script an image
// that is linked to its file still opens the file.
(function () {
  var main = document.querySelector("main.content");
  if (!main) return;
  var box = document.createElement("div");
  box.className = "lightbox";
  box.hidden = true;
  box.setAttribute("role", "dialog");
  box.setAttribute("aria-modal", "true");
  box.setAttribute("aria-label", "Image");
  box.innerHTML = '<button class="lightbox-close" type="button" aria-label="Close">×</button><img alt=""><p class="lightbox-caption"></p>';
  document.body.appendChild(box);
  var big = box.querySelector("img"), caption = box.querySelector(".lightbox-caption"), closeButton = box.querySelector("button");
  var opener = null;
  var imageFile = /\.(png|jpe?g|gif|svg|webp|avif)(\?.*)?$/i;

  function open(img) {
    var link = img.closest("a");
    opener = img;
    big.src = link && imageFile.test(link.getAttribute("href") || "") ? link.href : img.currentSrc || img.src;
    big.alt = img.alt;
    var figure = img.closest("figure"), text = figure && figure.querySelector("figcaption");
    caption.textContent = (text && text.textContent) || img.alt || "";
    box.hidden = false;
    document.body.classList.add("lightbox-open");
    closeButton.focus();
  }
  function close() {
    if (box.hidden) return;
    box.hidden = true;
    big.removeAttribute("src");
    document.body.classList.remove("lightbox-open");
    if (opener) opener.focus();
  }
  function zoomable(img) {
    var link = img.closest("a");
    // An image inside a link to a page keeps the link.
    return !(link && !imageFile.test(link.getAttribute("href") || "")) && !img.hasAttribute("data-no-lightbox");
  }

  main.querySelectorAll("img").forEach(function (img) {
    if (!zoomable(img)) return;
    img.classList.add("zoomable");
    img.tabIndex = 0;
    img.setAttribute("role", "button");
    if (!img.alt) img.setAttribute("aria-label", "Open the image");
  });
  main.addEventListener("click", function (e) {
    var img = e.target.closest && e.target.closest("img.zoomable");
    if (!img) return;
    e.preventDefault();
    open(img);
  });
  main.addEventListener("keydown", function (e) {
    var img = e.target;
    if (img.classList && img.classList.contains("zoomable") && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      open(img);
    }
  });
  box.addEventListener("click", function (e) { if (e.target !== big) close(); });
  document.addEventListener("keydown", function (e) { if (e.key === "Escape") close(); });
})();
