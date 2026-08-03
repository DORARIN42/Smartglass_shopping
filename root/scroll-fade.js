/* ==========================================================================
   Shopping XR — Scroll fade edges (companion script)
   CSS: interactions.css's .scroll-fade-edges. Fully self-discovering — just
   include this script on any page that has a `.scroll-fade-edges` element:

     <script src="../scroll-fade.js"></script>

   It toggles `.at-top` / `.at-bottom` on each matching element based on its
   own scroll position, so the CSS can suppress the fade at whichever edge
   is already fully scrolled-to (see interactions.css for why — it lets a
   list's first/last item read as "this is the end", not "there's more").
   ========================================================================== */
(function () {
  var EPSILON = 1; // px tolerance for sub-pixel scroll rounding

  function update(el) {
    var atTop = el.scrollTop <= EPSILON;
    var atBottom = el.scrollTop + el.clientHeight >= el.scrollHeight - EPSILON;
    el.classList.toggle('at-top', atTop);
    el.classList.toggle('at-bottom', atBottom);
  }

  function init() {
    var lists = document.querySelectorAll('.scroll-fade-edges');
    for (var i = 0; i < lists.length; i++) {
      (function (el) {
        update(el);
        el.addEventListener('scroll', function () { update(el); }, { passive: true });
        window.addEventListener('resize', function () { update(el); });
        // Images/fonts finishing after DOMContentLoaded can change scrollHeight.
        window.addEventListener('load', function () { update(el); });
      })(lists[i]);
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', init);
  } else {
    init();
  }
})();
