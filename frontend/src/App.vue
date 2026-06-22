<template>
  <div class="app-shell">
    <RouterView />
  </div>
</template>

<style>
:root {
  color-scheme: light;
  --ys-bg: #f5f2ea;
  --ys-bg-strong: #e8dccb;
  --ys-surface: rgba(255, 252, 246, 0.9);
  --ys-surface-strong: #fffaf2;
  --ys-border: rgba(84, 55, 23, 0.12);
  --ys-text: #2f2418;
  --ys-muted: #76624b;
  --ys-primary: #b65b2e;
  --ys-primary-deep: #8c3c18;
  --ys-accent: #1f7a6b;
  --ys-shadow: 0 24px 60px rgba(75, 44, 16, 0.12);
  font-family: 'Segoe UI Variable', 'Noto Sans SC', sans-serif;
  color: var(--ys-text);
  background:
    radial-gradient(circle at top left, rgba(182, 91, 46, 0.16), transparent 28%),
    radial-gradient(circle at top right, rgba(31, 122, 107, 0.12), transparent 26%),
    linear-gradient(180deg, #f9f5ee 0%, #f2ecdf 100%);
}

* {
  box-sizing: border-box;
}

html,
body,
#app {
  margin: 0;
  min-height: 100%;
}

body {
  min-height: 100vh;
}

a {
  color: inherit;
  text-decoration: none;
}

.app-shell {
  min-height: 100vh;
}
</style>
