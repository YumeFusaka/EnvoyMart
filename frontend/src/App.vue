<script setup lang="ts">
/**
 * 根组件。
 *
 * 这里**不放任何样式** —— 全局样式只有 `styles/` 一个来源。
 * 此前根组件里有一份非 scoped 的 `<style>`，把设计 token 原样抄了一遍，
 * 于是同一批变量在 `main.css` 和这里各有一份，改一个主色要动两个文件。
 */
</script>

<template>
  <RouterView />
</template>
