// Applies the saved appearance (light/dark and colour theme) before first paint, so there is no
// flash. Kept as a tiny external file because the Content-Security-Policy forbids inline scripts.
try {
  var theme = localStorage.getItem('untangle.theme');
  if (theme === 'light' || theme === 'dark') {
    document.documentElement.setAttribute('data-theme', theme);
  }
  var accent = localStorage.getItem('untangle.accent');
  if (accent && /^[a-z]{3,12}$/.test(accent)) {
    document.documentElement.setAttribute('data-accent', accent);
  }
} catch (e) {
  /* storage unavailable (private mode): follow the OS preference and the default colours */
}
