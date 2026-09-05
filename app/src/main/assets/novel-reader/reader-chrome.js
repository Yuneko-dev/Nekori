// Push reader-menu visibility into a freshly loaded page.
//
// Replaces:
//   __OBJECT__                             - global object name (Tsundoku)
//   __MENU_KEY__                           - runtime menu-visible key
//   __MENU_VISIBLE__                       - true / false
//   __EVENT__                              - menu-visibility CustomEvent name
//
(function () {
    window.__OBJECT__ = window.__OBJECT__ || {};
    window.__OBJECT__.runtime = window.__OBJECT__.runtime || {};
    var was = window.__OBJECT__.runtime.__MENU_KEY__;
    window.__OBJECT__.runtime.__MENU_KEY__ = __MENU_VISIBLE__;
    if (was !== __MENU_VISIBLE__) {
        window.dispatchEvent(new CustomEvent('__EVENT__', { detail: { visible: __MENU_VISIBLE__ } }));
    }
})();
