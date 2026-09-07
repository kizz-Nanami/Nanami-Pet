<template>
  <div class="glass-select" ref="rootRef">
    <button type="button" class="gs-trigger" :class="{ open }" @click="open = !open">
      <span class="gs-label">{{ currentLabel }}</span>
      <span class="gs-arrow" :class="{ up: open }">▾</span>
    </button>
    <!-- ★ 毛玻璃下拉面板：与设置窗口材质统一，替代原生 select 的系统白列表 -->
    <div v-if="open" class="gs-panel">
      <div v-for="opt in options" :key="opt.value" class="gs-option"
        :class="{ active: opt.value === modelValue }" @click="choose(opt.value)">{{ opt.label }}</div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from "vue"

const props = defineProps<{
  modelValue: string
  options: { value: string; label: string }[]
}>()
const emit = defineEmits<{ (e: "update:modelValue", v: string): void }>()

const open = ref(false)
const rootRef = ref<HTMLElement>()

const currentLabel = computed(() =>
  props.options.find((o) => o.value === props.modelValue)?.label ?? props.modelValue)

function choose(v: string) {
  emit("update:modelValue", v)
  open.value = false
}
// 点击面板外或按 Esc 关闭
function onDocClick(e: MouseEvent) {
  if (open.value && rootRef.value && !rootRef.value.contains(e.target as Node)) open.value = false
}
function onKey(e: KeyboardEvent) {
  if (e.key === "Escape") open.value = false
}
onMounted(() => {
  document.addEventListener("mousedown", onDocClick)
  document.addEventListener("keydown", onKey)
})
onUnmounted(() => {
  document.removeEventListener("mousedown", onDocClick)
  document.removeEventListener("keydown", onKey)
})
</script>

<style scoped>
.glass-select { position: relative; min-width: 0; }
.gs-trigger {
  display: flex; align-items: center; justify-content: space-between; gap: 8px;
  width: 100%;
  padding: 7px 12px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: linear-gradient(135deg, rgba(255, 255, 255, 0.62), rgba(255, 255, 255, 0.42));
  color: #2a3242;
  font-size: 13px;
  cursor: pointer;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}
.gs-trigger:hover { border-color: rgba(59, 130, 246, 0.35); }
.gs-trigger.open {
  border-color: rgba(59, 130, 246, 0.5);
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.12);
}
.gs-label {
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  text-align: left;
}
.gs-arrow { flex-shrink: 0; font-size: 11px; color: #4d5665; transition: transform 0.18s; }
.gs-arrow.up { transform: rotate(180deg); }

.gs-panel {
  position: absolute;
  top: calc(100% + 6px);
  left: 0; right: 0;
  z-index: 50;
  max-height: 220px;
  overflow-y: auto;
  padding: 5px;
  border-radius: 12px;
  border: 1px solid rgba(255, 255, 255, 0.55);
  background: linear-gradient(160deg, rgba(252, 253, 255, 0.92), rgba(240, 244, 250, 0.88));
  backdrop-filter: blur(24px) saturate(1.5);
  -webkit-backdrop-filter: blur(24px) saturate(1.5);
  box-shadow: 0 10px 30px rgba(30, 40, 60, 0.18);
}
.gs-option {
  padding: 7px 10px;
  border-radius: 8px;
  font-size: 13px;
  color: #2a3242;
  cursor: pointer;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  transition: background 0.12s;
}
.gs-option:hover { background: rgba(59, 130, 246, 0.1); }
.gs-option.active {
  background: rgba(59, 130, 246, 0.14);
  color: #2563eb;
  font-weight: 500;
}
.gs-panel::-webkit-scrollbar { width: 6px; }
.gs-panel::-webkit-scrollbar-thumb { background: rgba(100, 120, 150, 0.25); border-radius: 3px; }
</style>
