try {
  const theme = localStorage.getItem('autocuidado.theme');
  if (theme) document.documentElement.dataset.theme = theme;
} catch {
  // The default palette remains available without browser storage.
}
