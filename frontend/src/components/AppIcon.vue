<script setup>
defineProps({ name: { type: String, default: 'document' }, size: { type: Number, default: 20 } })
const paths = {
  grid: 'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
  book: 'M12 5c-3-2-6-2-9-1v15c3-1 6-1 9 1 3-2 6-2 9-1V4c-3-1-6-1-9 1z M12 5v15',
  document: 'M14 3H5v18h14V8z M14 3v5h5 M8 12h8 M8 16h6',
  layers: 'm12 3 10 5-10 5L2 8z M2 12l10 5 10-5 M2 16l10 5 10-5',
  check: 'M9 4H4v17h16V4h-5 M9 2h6v5H9z M8 13l3 3 5-6',
  users: 'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8 M18 4a4 4 0 0 1 0 7 M22 21v-2a4 4 0 0 0-3-4',
  shield: 'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6z M8 12l3 3 5-6',
  clock: 'M12 3a9 9 0 1 0 0 18 9 9 0 0 0 0-18 M12 7v5l3 2',
  arrow: 'M5 12h14 M13 6l6 6-6 6',
  menu: 'M3 6h18 M3 12h18 M3 18h18',
  close: 'm6 6 12 12 M6 18 18 6',
}
</script>

<template>
  <svg :width="size" :height="size" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <path :d="paths[name] || paths.document" />
  </svg>
</template>
