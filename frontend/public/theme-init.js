// Applies the saved theme before first paint (no light/dark flash). Kept as a tiny external file
// because the Content-Security-Policy forbids inline scripts.
try {
  var theme = localStorage.getItem('untangle.theme');
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.setAttribute('data-theme', theme);
  }
} catch (e) {
  /* storage unavailable (private mode): follow the OS preference */
}
