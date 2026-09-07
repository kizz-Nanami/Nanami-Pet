// ★ 模型管理：当前模型（启动时读持久化配置，设置窗口切换后广播到两窗口）
import { ref, computed, watch } from "vue"
import { modelConfig } from "../config"

export function useModelManager(opts: { isSettings: boolean; ipc: any }) {
  const { isSettings, ipc } = opts
  const modelCfg = ref({ folder: modelConfig.modelName, file: modelConfig.modelFile })
  const modelList = ref<{ folder: string; file: string; expressions: string[] }[]>([])
  const selectedModelKey = ref("")
  // setup 同步读取持久化配置（早于 Live2DCanvas mount，首屏即加载正确的模型）
  if (ipc?.sendSync) {
    const saved = ipc.sendSync("get-model-config")
    if (saved?.folder) modelCfg.value = { folder: saved.folder, file: saved.file }
  }
  // 切换模型 → 持久化 + 广播（宠物窗口收到后经 props 变化自动重建模型）
  watch(selectedModelKey, (key) => {
    const m = modelList.value.find((x) => x.folder + "/" + x.file === key)
    if (m && (m.folder !== modelCfg.value.folder || m.file !== modelCfg.value.file)) {
      ipc?.send?.("set-model-config", { folder: m.folder, file: m.file })
    }
  })
  function refreshModels() {
    if (!ipc?.sendSync) return
    modelList.value = ipc.sendSync("list-models") || []
    selectedModelKey.value = modelCfg.value.folder + "/" + modelCfg.value.file
  }
  // ★ 模型下拉选项（GlassSelect 通用格式），显示文案与原 select 的 option 一致
  const modelOptions = computed(() =>
    modelList.value.map((m) => ({
      value: m.folder + "/" + m.file,
      label: m.folder + (m.expressions.length ? `（${m.expressions.length}个表情）` : ""),
    })))
  if (isSettings) refreshModels()
  // 广播回声：任一窗口切换模型后两窗口同步（宠物窗口 props 变化 → 自动重建模型）
  ipc?.on?.("model-changed", (_e: any, model: { folder: string; file: string }) => {
    if (model?.folder) {
      modelCfg.value = { folder: model.folder, file: model.file }
      if (isSettings) selectedModelKey.value = model.folder + "/" + model.file
    }
  })
  return { modelCfg, modelList, selectedModelKey, modelOptions, refreshModels }
}
