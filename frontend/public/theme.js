try {
  const theme = localStorage.getItem('autocuidado.theme');
  if (['light', 'dark', 'pink'].includes(theme)) document.documentElement.dataset.theme = theme;
} catch {
  // Defaults remain available without browser storage.
}
