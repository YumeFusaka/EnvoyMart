<script setup lang="ts">
/**
 * 商品编辑页（新建 / 编辑共用）。
 *
 * 这个页面真正的难点不是表单，是**规格与 SKU 的对应关系**：
 *
 * 1. 规格组一变，组合就要重算（笛卡尔积）。重算出来的组合必须回头与既有 SKU
 *    按「规格名→规格值」对上号、把 SKU 的 `id` 带过来。不带 id 提交等于告诉后端
 *    「这些都是新规格」，后端会删掉旧的 —— 而被订单引用过的 SKU 是删不掉的，
 *    整次保存直接失败，且失败原因（哪个规格）在表单上完全看不出来。
 * 2. 组合减少意味着**真的会删 SKU**。这件事必须当场说清楚，不能等保存失败才让运营去猜。
 *
 * 价格输入用「元」，进出这一层就换成分 —— 全项目金额内部一律是分。
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowLeft, Plus, RefreshRight, Delete } from '@element-plus/icons-vue'
import { createSpu, getSpu, updateSpu } from '@/api/admin/product'
import { uploadProductImage } from '@/api/admin/media'
import { listAttributes, listBrands, listCategoryTree } from '@/api/admin/catalog'
import { getProductDocuments } from '@/api/admin/knowledge'
import { formatPrice, formatPriceRange } from '@/api/product'
import { parseYuan, toYuan } from '@/utils/format'
import ErrorState from '@/components/ui/ErrorState.vue'
import type {
  AdminAttribute,
  AdminSku,
  AttributeRequest,
  SkuRequest,
  SpecRequest,
  SpuUpsertRequest,
} from '@/types/admin'
import type { BrandView, CategoryNode } from '@/types/models'

const route = useRoute()
const router = useRouter()

const spuId = computed(() => {
  const raw = route.params.id
  return raw === undefined || Array.isArray(raw) ? null : Number(raw)
})
const isEdit = computed(() => spuId.value !== null)

const loading = ref(true)
const saving = ref(false)
const loadError = ref<string | null>(null)

const categoryTree = ref<CategoryNode[]>([])
const brands = ref<BrandView[]>([])
/** 当前类目的参数模板。切类目要重拉 —— 模板挂在类目上，不是全局的 */
const attributeTemplate = ref<AdminAttribute[]>([])

// ==================== 表单状态 ====================

interface SpecDraft {
  name: string
  values: string[]
}

interface SkuDraft {
  /** 既有 SKU 才有。null 表示这是本次新加的规格组合 */
  id: number | null
  skuCode: string | null
  status: number
  image: string | null
  /** 元。展示与输入都用它，提交时才换算成「分」 */
  price: string
  originalPrice: string
  stock: number
  specValues: Record<string, string>
}

const form = reactive({
  name: '',
  subtitle: '',
  spuCode: '',
  categoryId: undefined as number | undefined,
  brandId: undefined as number | undefined,
  mainImage: '',
  images: [] as string[],
  detailHtml: '',
  tags: [] as string[],
  status: 0,
})

const specs = ref<SpecDraft[]>([])
const skus = ref<SkuDraft[]>([])
/**
 * 进页面时**库里那批** SKU 的快照，之后不再改。
 *
 * 重算组合时必须以它为基准，而不是以当前的 {@link skus} 为基准：当前这批是上一次重算的
 * 产物，被上一次剔掉的组合已经不在里面了。只认当前这批的后果是「手滑改了规格、再改回去」
 * 之后那个 SKU 的 id 找不回来 —— 提交时后端按「请求里没有的就删」把它删掉、再按新组合建一个，
 * id 变了、历史被切成两段，而界面上什么都没提示。
 */
const loadedSkus = ref<SkuDraft[]>([])
/** 本次重算后会从库里消失的 SKU。保存前必须让运营看见 */
const droppedSkus = ref<SkuDraft[]>([])
/** 参数值：attributeId → 值文本 */
const attributeValues = reactive<Record<number, string>>({})

// ==================== 加载 ====================

onMounted(async () => {
  try {
    const [tree, brandList] = await Promise.all([
      listCategoryTree().catch(() => [] as CategoryNode[]),
      listBrands().catch(() => [] as BrandView[]),
    ])
    categoryTree.value = tree
    brands.value = brandList

    if (spuId.value !== null) {
      const detail = await getSpu(spuId.value)
      form.name = detail.name
      form.subtitle = detail.subtitle ?? ''
      form.spuCode = detail.spuCode
      form.categoryId = detail.categoryId
      form.brandId = detail.brandId ?? undefined
      form.mainImage = detail.mainImage ?? ''
      form.images = [...detail.images]
      form.detailHtml = detail.detailHtml ?? ''
      form.tags = [...detail.tags]
      form.status = detail.status

      specs.value = detail.specs.map((group) => ({
        name: group.name,
        values: group.values.map((v) => v.value),
      }))
      // 两份独立副本：loadedSkus 此后不再动，代表「库里有什么」，重算组合时以它为基准；
      // skus 是运营的工作集，会被整体替换（见 loadedSkus、regenerateSkus）
      loadedSkus.value = detail.skus.map(toDraft)
      skus.value = detail.skus.map(toDraft)
      for (const attribute of detail.attributes) {
        attributeValues[attribute.attributeId] = attribute.value
      }
      await loadAttributeTemplate(detail.categoryId)
    }
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
})

function toDraft(sku: AdminSku): SkuDraft {
  return {
    id: sku.id,
    skuCode: sku.skuCode,
    status: sku.status ?? 1,
    image: sku.image,
    price: toYuan(sku.price),
    originalPrice: toYuan(sku.originalPrice),
    stock: sku.stock,
    specValues: { ...sku.specValues },
  }
}

/** 切类目要重拉模板，并且把已填但不在新模板里的值丢掉 —— 留着它们提交上去会被后端拒 */
async function loadAttributeTemplate(categoryId: number | undefined) {
  if (categoryId === undefined || categoryId === null) {
    attributeTemplate.value = []
    return
  }
  attributeTemplate.value = await listAttributes(categoryId).catch(() => [])
  const known = new Set(attributeTemplate.value.map((item) => item.id))
  for (const key of Object.keys(attributeValues)) {
    if (!known.has(Number(key))) {
      delete attributeValues[Number(key)]
    }
  }
}

/** 用户取消切换类目时要把选择回滚，而回滚本身又会触发一次 watch —— 用它把那次忽略掉 */
let revertingCategory = false
watch(
  () => form.categoryId,
  async (next, previous) => {
    if (revertingCategory) {
      revertingCategory = false
      return
    }
    // 首次回显时 onMounted 已经拉过一次，别拉两遍
    if (loading.value) {
      return
    }
    if (next !== undefined && next !== null && Object.keys(attributeValues).length > 0) {
      try {
        await ElMessageBox.confirm('切换类目会清掉已填的商品参数，确定继续？', '切换类目', {
          type: 'warning',
          confirmButtonText: '继续',
          cancelButtonText: '取消',
        })
      } catch {
        revertingCategory = true
        form.categoryId = previous
        return
      }
    }
    await loadAttributeTemplate(next)
  },
)

// ==================== 规格与 SKU ====================

function addSpec() {
  specs.value.push({ name: '', values: [] })
}

function removeSpec(index: number) {
  const spec = specs.value[index]
  // 拿 trim 后的名字去比对：组合键里的规格名是 trim 过的（见 regenerateSkus），
  // 用原文查会查不到，于是「明明还用在一堆组合上」的规格被当成没人用而删掉
  const name = spec?.name.trim() ?? ''
  const affected = name
    ? skus.value.filter((sku) => sku.specValues[name] !== undefined).length
    : 0
  if (affected > 0) {
    ElMessage.warning(`「${name}」还用在这 ${affected} 个规格组合上，先删掉组合再删规格`)
    return
  }
  specs.value.splice(index, 1)
}

/**
 * 规格组合的稳定键。
 * <p>
 * 按规格名排序后交给 JSON 序列化，而不是依赖 `Object.keys` 的顺序 —— 那个顺序取决于对象
 * 怎么被构造出来的，两次看起来一样的组合可能算出两个不同的键，于是「既有 SKU 对不上号」、
 * `id` 全丢。
 * <p>
 * 也不用 `名:值;名:值` 那种手拼分隔符：规格值里出现分隔符就会串味，
 * 「规格 A = b:c」与「规格 A:b = c」会拼成同一个键，两个不同的组合被判成同一个，
 * 于是其中一个的 id 被安到另一个头上 —— 保存后两个 SKU 互相换了价格库存。
 * JSON 会转义引号与分隔符，不存在这种歧义。
 */
function comboKey(specValues: Record<string, string>): string {
  return JSON.stringify(Object.entries(specValues).sort(([a], [b]) => a.localeCompare(b)))
}

const specNameCount = computed(() => {
  const seen = new Map<string, number>()
  for (const spec of specs.value) {
    const name = spec.name.trim()
    if (name) {
      seen.set(name, (seen.get(name) ?? 0) + 1)
    }
  }
  return seen
})

const duplicateSpecName = computed(
  () => [...specNameCount.value.entries()].find(([, n]) => n > 1)?.[0] ?? null,
)

const canGenerate = computed(
  () =>
    specs.value.length > 0 &&
    specs.value.every((s) => s.name.trim() && s.values.length > 0) &&
    !duplicateSpecName.value,
)

const comboCount = computed(() =>
  specs.value.reduce((acc, spec) => acc * Math.max(spec.values.length, 1), 1),
)

/**
 * 重算规格组合。
 * <p>
 * 与既有 SKU 按组合键对上号的会**带着 id 与已填的价格库存**保留下来；
 * 对不上号的是新组合。反过来，没有被任何组合覆盖到的既有 SKU 会被记进
 * {@link droppedSkus} —— 提交时后端会真的删掉它们。
 */
function regenerateSkus() {
  if (!canGenerate.value) {
    return
  }
  const groups = specs.value.map((spec) => ({
    name: spec.name.trim(),
    // 值也 trim：多打一个空格就是另一个组合键，与库里的 SKU 对不上号，
    // 表现是保存后多出一个只差空格的重复 SKU
    values: spec.values.map((value) => value.trim()).filter(Boolean),
  }))
  // trim 之后才可能暴露出「这一项一个值都不剩」。此时若继续算，叉乘结果为空集，
  // 于是所有既有 SKU 都被判成「会消失」，一次误点就清空整个商品的规格组合
  if (groups.length === 0 || groups.some((group) => group.values.length === 0)) {
    ElMessage.warning('每个规格项至少要有一个值')
    return
  }

  let combos: Record<string, string>[] = [{}]
  for (const group of groups) {
    const next: Record<string, string>[] = []
    for (const base of combos) {
      for (const value of group.values) {
        next.push({ ...base, [group.name]: value })
      }
    }
    combos = next
  }

  // 池子以**库里那批**为底、当前工作集盖在上面。只从当前工作集建池是不够的：
  // 上一次重算剔掉的组合已经不在里面了，于是「手滑改了规格、再改回去」时那个 SKU 的 id
  // 找不回来 —— 提交时后端按「请求里没有的就删」把它删掉、同一组合又以新 id 建一遍，
  // id 变了、评价与销量的关联被切断，而界面上什么都没提示
  const pool = new Map<string, SkuDraft>()
  for (const sku of loadedSkus.value) {
    pool.set(comboKey(sku.specValues), sku)
  }
  for (const sku of skus.value) {
    pool.set(comboKey(sku.specValues), sku)
  }

  // 「会从库里消失的」同样以库里那批为准。拿当前工作集算的话，恰恰是上面那种
  // 「改出去再改回来」的情形会算出空集：提示不弹，SKU 却被真的删了
  const keptKeys = new Set(combos.map(comboKey))
  droppedSkus.value = loadedSkus.value.filter(
    (sku) => sku.id !== null && !keptKeys.has(comboKey(sku.specValues)),
  )

  skus.value = combos.map((combo) => {
    const key = comboKey(combo)
    const previous = pool.get(key)
    return {
      id: previous?.id ?? null,
      skuCode: previous?.skuCode ?? null,
      status: previous?.status ?? 1,
      image: previous?.image ?? null,
      price: previous?.price ?? '',
      originalPrice: previous?.originalPrice ?? '',
      stock: previous?.stock ?? 0,
      specValues: combo,
    }
  })
  ElMessage.success(`已生成 ${combos.length} 个规格组合`)
}

/** 只有一个规格值时不必让运营去打勾：那就是「这款商品只有这一个规格」 */
function comboText(sku: SkuDraft): string {
  return Object.entries(sku.specValues)
    .map(([name, value]) => `${name}：${value}`)
    .join(' / ')
}

// ==================== 提交 ====================

/** 校验结果：返回第一条错误，全部通过则返回 null */
function validate(): string | null {
  if (!form.name.trim()) {
    return '商品名不能为空'
  }
  if (form.categoryId === undefined) {
    return '请选择类目'
  }
  if (duplicateSpecName.value) {
    return `规格名「${duplicateSpecName.value}」重复了`
  }
  if (skus.value.length === 0) {
    return '至少要有一个规格组合，否则商品没有可卖的规格'
  }
  for (const sku of skus.value) {
    if (parseYuan(sku.price) === null) {
      return `「${comboText(sku)}」没填价格或价格格式不对`
    }
    if (sku.stock < 0 || !Number.isInteger(sku.stock)) {
      return `「${comboText(sku)}」的库存必须是非负整数`
    }
  }
  return null
}

async function save() {
  const problem = validate()
  if (problem) {
    ElMessage.warning(problem)
    return
  }
  if (droppedSkus.value.length > 0) {
    try {
      await ElMessageBox.confirm(
        `本次保存会删除 ${droppedSkus.value.length} 个规格：${droppedSkus.value.map(comboText).join('、')}。` +
          '被历史订单引用过的规格删不掉，保存会被拒绝，那时请改用「停用」。',
        '删除规格确认',
        { type: 'warning', confirmButtonText: '确定删除', cancelButtonText: '返回检查' },
      )
    } catch {
      return
    }
  }

  const payload: SpuUpsertRequest = {
    name: form.name.trim(),
    subtitle: form.subtitle.trim() || undefined,
    categoryId: form.categoryId as number,
    brandId: form.brandId ?? null,
    mainImage: form.mainImage.trim() || undefined,
    images: form.images.map((url) => url.trim()).filter(Boolean),
    detailHtml: form.detailHtml.trim() || undefined,
    tags: form.tags.map((tag) => tag.trim()).filter(Boolean),
    status: form.status,
    specs: specs.value.map<SpecRequest>((spec) => ({
      name: spec.name.trim(),
      values: spec.values.map((value) => value.trim()).filter(Boolean),
    })),
    skus: skus.value.map<SkuRequest>((sku) => ({
      // 带 id 是「改」，不带才是「新建」——见 reconcileSkus 的说明
      id: sku.id,
      skuCode: sku.skuCode,
      price: parseYuan(sku.price) as number,
      originalPrice: parseYuan(sku.originalPrice),
      stock: sku.stock,
      image: sku.image,
      status: sku.status,
      specValues: sku.specValues,
    })),
    attributes: buildAttributes(),
  }
  // 编辑时留空表示「不动原编码」，而不是把编码清掉
  if (!isEdit.value) {
    payload.spuCode = form.spuCode.trim() || undefined
  }

  saving.value = true
  try {
    if (spuId.value === null) {
      const created = await createSpu(payload)
      ElMessage.success('商品已创建')
      await router.replace(`/admin/products/${created}/edit`)
    } else {
      await updateSpu(spuId.value, payload)
      ElMessage.success('已保存')
    }
  } catch {
    // 拒绝原因由后端给出（例如「删掉的规格被订单引用」），拦截器已展示
  } finally {
    saving.value = false
  }
}

function buildAttributes(): AttributeRequest[] {
  const out: AttributeRequest[] = []
  for (const item of attributeTemplate.value) {
    const value = (attributeValues[item.id] ?? '').trim()
    if (value) {
      out.push({ attributeId: item.id, value })
    }
  }
  return out
}

// ==================== 图片 ====================

/**
 * 正在上传的目标：`main` 表示主图，数字表示图集里的第几张。
 * 它是「哪一行在转圈」的唯一依据 —— 不给按钮做 loading 时，用户会连点三次，
 * 传上去三张只有文件名不同的图，还得自己删两张。
 */
const uploading = ref<'main' | number | null>(null)

/**
 * 上传一张图并回填到目标位置。
 *
 * 不做「上传即保存」：这里只把返回的 URL 写进表单，真正的落库仍在保存按钮那一步。
 * 否则传一张图就改一次数据库，用户点「取消」时已经改了一半。
 */
async function handleUpload(target: 'main' | number, event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  // 先清空 input 的 value：不清的话，连续选同一个文件不会触发 change，
  // 表现是「第二次上传没反应」，而文件其实一模一样、没有任何报错
  input.value = ''
  if (!file) {
    return
  }
  uploading.value = target === 'main' ? 'main' : target
  try {
    const url = await uploadProductImage(file)
    if (target === 'main') {
      form.mainImage = url
    } else if (target < form.images.length) {
      // 再次校验下标：上传是异步的，期间用户可能已经删掉了那一行
      form.images[target] = url
    }
    ElMessage.success('图片已上传')
  } catch {
    // 错误提示由 axios 拦截器统一弹出，这里只需保证 loading 归位
  } finally {
    uploading.value = null
  }
}

function addImage() {
  form.images.push('')
}

function removeImage(index: number) {
  form.images.splice(index, 1)
}

function moveImage(index: number, delta: number) {
  const target = index + delta
  if (target < 0 || target >= form.images.length) {
    return
  }
  const [moved] = form.images.splice(index, 1)
  // 下标越界时 splice 返回空数组，`moved` 会是 undefined —— 直接插进去会往图集里塞一个空洞
  if (moved !== undefined) {
    form.images.splice(target, 0, moved)
  }
}

const statusOptions = [
  { label: '下架（不对外展示）', value: 0 },
  { label: '上架', value: 1 },
]

const totalStock = computed(() => skus.value.reduce((sum, sku) => sum + (sku.stock || 0), 0))
const priceRange = computed(() => {
  const cents = skus.value.map((sku) => parseYuan(sku.price)).filter((v): v is number => v !== null)
  if (cents.length === 0) {
    return '—'
  }
  return formatPriceRange(Math.min(...cents), Math.max(...cents))
})

/**
 * 该商品在知识图谱里被哪些文档支持 —— 「说明书接上了没有」。
 *
 * 读图谱而不是文档表：两者没有外键，绑定是构建期实体链接的结果。
 * 拉取失败不弹错、只留空列表：这是一个**辅助信息面板**，不该因为它拿不到
 * 就让整个商品编辑页看起来出错。拿不到与「确实没有说明书」在这里不做区分，
 * 因为对使用者来说下一步动作是一样的——去传一篇。
 */
const productDocs = ref<{ docNo: string; title: string; relations: number }[]>([])
const docsLoading = ref(false)

async function loadProductDocs() {
  if (spuId.value === null) return
  docsLoading.value = true
  try {
    productDocs.value = await getProductDocuments(`spu${spuId.value}`)
  } catch {
    productDocs.value = []
  } finally {
    docsLoading.value = false
  }
}

// 保存后重新拉：新传的说明书要经过一次图谱构建才会出现在这里，
// 所以这个面板天然是「刚才那次上传接上了没有」的即时反馈
watch(spuId, () => { if (isEdit.value) void loadProductDocs() }, { immediate: true })

</script>

<template>
  <div class="admin-panel">
    <div class="admin-toolbar">
      <el-button :icon="ArrowLeft" text @click="router.push('/admin/products')">返回列表</el-button>
      <h2 class="admin-toolbar__title">{{ isEdit ? '编辑商品' : '新建商品' }}</h2>
      <span v-if="isEdit" class="admin-toolbar__count">{{ form.spuCode }}</span>
      <div class="admin-toolbar__actions">
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </div>

    <ErrorState v-if="loadError" :message="loadError" :on-retry="() => router.go(0)" />

    <el-skeleton v-else-if="loading" :rows="8" animated />

    <template v-else>
      <section class="admin-detail">
        <h3 class="admin-section__title">基本信息</h3>
        <el-form label-width="96px" label-position="top" class="edit-grid">
          <el-form-item label="商品名" required class="edit-grid__wide">
            <el-input
              v-model="form.name"
              maxlength="120"
              show-word-limit
              placeholder="如：维生素 D3 软胶囊"
            />
          </el-form-item>

          <el-form-item label="副标题" class="edit-grid__wide">
            <el-input
              v-model="form.subtitle"
              maxlength="160"
              show-word-limit
              placeholder="一句话卖点"
            />
          </el-form-item>

          <el-form-item label="商品编码">
            <el-input
              v-model="form.spuCode"
              :disabled="isEdit"
              :placeholder="isEdit ? '编码是对外可见的业务标识，建后不可改' : '留空自动生成'"
            />
          </el-form-item>

          <el-form-item label="类目" required>
            <el-tree-select
              v-model="form.categoryId"
              :data="categoryTree"
              :props="{ label: 'name', value: 'id', children: 'children' }"
              node-key="id"
              check-strictly
              clearable
              placeholder="选择类目"
              style="width: 100%"
            />
          </el-form-item>

          <el-form-item label="品牌">
            <el-select v-model="form.brandId" clearable placeholder="无品牌" style="width: 100%">
              <el-option v-for="b in brands" :key="b.id" :label="b.name" :value="b.id" />
            </el-select>
          </el-form-item>

          <el-form-item label="状态">
            <el-select v-model="form.status" style="width: 100%">
              <el-option
                v-for="s in statusOptions"
                :key="s.value"
                :label="s.label"
                :value="s.value"
              />
            </el-select>
          </el-form-item>

          <el-form-item label="标签" class="edit-grid__wide">
            <el-select
              v-model="form.tags"
              multiple
              filterable
              allow-create
              default-first-option
              :reserve-keyword="false"
              placeholder="回车添加，如「进口」「孕婴可用」"
              style="width: 100%"
            />
          </el-form-item>

          <el-form-item label="主图 URL" class="edit-grid__wide">
            <div class="image-input">
              <el-input v-model="form.mainImage" placeholder="https://… 或点右侧上传" />
              <label class="image-input__upload">
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/gif,image/webp"
                  :disabled="uploading !== null"
                  @change="handleUpload('main', $event)"
                />
                <el-button :loading="uploading === 'main'" :disabled="uploading !== null">
                  上传图片
                </el-button>
              </label>
            </div>
          </el-form-item>

          <el-form-item label="详情 HTML" class="edit-grid__wide">
            <el-input
              v-model="form.detailHtml"
              type="textarea"
              :rows="5"
              placeholder="商品详情的富文本片段"
            />
          </el-form-item>
        </el-form>
      </section>

      <section class="admin-detail">
        <h3 class="admin-section__title">商品图集</h3>
        <p class="admin-dialog__hint">顺序即前台展示顺序，第一张通常用主图。</p>
        <div v-for="(_, index) in form.images" :key="index" class="image-row">
          <el-image class="image-row__thumb" :src="form.images[index] || undefined" fit="cover">
            <template #error>
              <div class="image-row__thumb image-row__thumb--blank" aria-hidden="true">无图</div>
            </template>
          </el-image>
          <el-input v-model="form.images[index]" placeholder="https://… 或点右侧上传" />
          <label class="image-input__upload">
            <input
              type="file"
              accept="image/jpeg,image/png,image/gif,image/webp"
              :disabled="uploading !== null"
              @change="handleUpload(index, $event)"
            />
            <el-button :loading="uploading === index" :disabled="uploading !== null" text>
              上传
            </el-button>
          </label>
          <el-button :disabled="index === 0" text @click="moveImage(index, -1)">上移</el-button>
          <el-button :disabled="index === form.images.length - 1" text @click="moveImage(index, 1)"
            >下移</el-button
          >
          <el-button :icon="Delete" text type="danger" @click="removeImage(index)" />
        </div>
        <el-button :icon="Plus" text type="primary" @click="addImage">添加一张</el-button>
      </section>

      <section class="admin-detail">
        <h3 class="admin-section__title">规格</h3>
        <p class="admin-dialog__hint">
          规格组决定商品有哪些可选项；改完点「重算组合」，下面的 SKU 表会跟着更新。
          已经是这个商品的规格会连同价格库存一起保留。
        </p>

        <div v-for="(spec, index) in specs" :key="index" class="spec-row">
          <el-input v-model="spec.name" class="spec-row__name" placeholder="规格名，如「净含量」" />
          <el-select
            v-model="spec.values"
            multiple
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
            placeholder="规格值，回车添加"
            class="spec-row__values"
          />
          <el-button :icon="Delete" text type="danger" @click="removeSpec(index)" />
        </div>

        <div class="spec-actions">
          <el-button :icon="Plus" text type="primary" @click="addSpec">添加规格组</el-button>
          <el-button :icon="RefreshRight" :disabled="!canGenerate" @click="regenerateSkus">
            重算组合（{{ comboCount }} 个）
          </el-button>
        </div>
        <el-alert
          v-if="duplicateSpecName"
          type="error"
          :closable="false"
          show-icon
          :title="`规格名「${duplicateSpecName}」出现了两次，重算组合前请先合并`"
        />
      </section>

      <section class="admin-detail">
        <h3 class="admin-section__title">
          SKU
          <span class="admin-toolbar__count"
            >共 {{ skus.length }} 个，合计库存 {{ totalStock }}，价格区间 {{ priceRange }}</span
          >
        </h3>

        <el-alert
          v-if="droppedSkus.length > 0"
          type="warning"
          :closable="false"
          show-icon
          class="sku-warning"
          :title="`保存会删除 ${droppedSkus.length} 个规格`"
          :description="droppedSkus.map(comboText).join('、')"
        />

        <div class="admin-table">
          <el-table :data="skus" style="width: 100%">
            <el-table-column label="规格组合" min-width="200">
              <template #default="{ row }">
                <span class="admin-cell--strong">{{ comboText(row) }}</span>
                <div v-if="row.id === null" class="admin-cell--tiny">本次新增</div>
              </template>
            </el-table-column>

            <el-table-column label="编码" width="180">
              <template #default="{ row }">
                <el-input v-model="row.skuCode" placeholder="留空自动生成" />
              </template>
            </el-table-column>

            <el-table-column label="售价（元）" width="140">
              <template #default="{ row }">
                <el-input v-model="row.price" placeholder="0.00" />
              </template>
            </el-table-column>

            <el-table-column label="原价（元）" width="140">
              <template #default="{ row }">
                <el-input v-model="row.originalPrice" placeholder="可空" />
              </template>
            </el-table-column>

            <el-table-column label="库存" width="140">
              <template #default="{ row }">
                <el-input-number
                  v-model="row.stock"
                  :min="0"
                  :step="1"
                  :controls="false"
                  style="width: 100%"
                />
              </template>
            </el-table-column>

            <el-table-column label="状态" width="110">
              <template #default="{ row }">
                <el-select v-model="row.status">
                  <el-option label="在售" :value="1" />
                  <el-option label="停用" :value="0" />
                </el-select>
              </template>
            </el-table-column>

            <el-table-column label="现价" width="100" align="right">
              <template #default="{ row }">
                <span class="admin-cell--num">{{ formatPrice(parseYuan(row.price)) }}</span>
              </template>
            </el-table-column>

            <template #empty>
              <p class="admin-empty">还没有规格组合。先在上面定义规格组，再点「重算组合」。</p>
            </template>
          </el-table>
        </div>
        <p class="admin-dialog__hint">
          库存是单独一条带条件的更新路径：保存时若库存已被其它操作改过，会提示刷新重试，
          不会把这段时间买家下单扣掉的量覆盖回去。
        </p>
      </section>

      <section v-if="form.categoryId !== undefined" class="admin-detail">
        <h3 class="admin-section__title">商品参数</h3>
        <p v-if="attributeTemplate.length === 0" class="admin-empty">
          这个类目还没有参数模板。在「类目与品牌」里给类目加上模板后，这里才会出现输入框。
        </p>
        <el-form v-else label-position="top" class="edit-grid">
          <el-form-item
            v-for="item in attributeTemplate"
            :key="item.id"
            :label="item.unit ? `${item.name}（${item.unit}）` : item.name"
          >
            <el-input
              v-model="attributeValues[item.id]"
              :placeholder="item.inputType === 'number' ? '数字' : '文本'"
            />
          </el-form-item>
        </el-form>
      </section>

      <!-- 说明书覆盖：商品与说明书在库里没有外键，绑定是图谱构建期实体链接的结果。
           所以这一栏读的是图，也只有**保存过并重建过索引**的文档才会出现。 -->
      <section v-if="isEdit" class="admin-detail">
        <h3 class="admin-section__title">说明书与知识依据</h3>
        <p v-if="docsLoading" class="admin-empty">正在读取…</p>
        <template v-else-if="productDocs.length > 0">
          <p class="docs-hint">
            这个商品当前有 {{ productDocs.length }} 篇文档在知识图谱里支持它。
          </p>
          <ul class="docs-list">
            <li v-for="doc in productDocs" :key="doc.docNo">
              <RouterLink :to="`/knowledge/${doc.docNo}`">{{ doc.title || doc.docNo }}</RouterLink>
              <span class="docs-list__meta">{{ doc.docNo }} · {{ doc.relations }} 条关系</span>
            </li>
          </ul>
        </template>
        <p v-else class="admin-empty">
          这个商品在知识图谱里还没有任何文档支持。上传它的说明书到
          <RouterLink to="/admin/knowledge">知识库管理</RouterLink>
          后，这里会出现对应的文档。
          <br />
          <span class="docs-hint">
            注意：说明书正文里要写到这个商品，且图谱重建成功之后才会出现——
            上传与建图是两步。
          </span>
        </p>
      </section>

      <div class="edit-foot">
        <el-button @click="router.push('/admin/products')">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </div>
    </template>
  </div>
</template>

<style scoped>
.docs-hint {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
  line-height: 1.6;
}

.docs-list {
  margin: 0;
  padding: 0;
  list-style: none;
  display: grid;
  gap: 6px;
}

.docs-list li {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
  padding: 6px 10px;
  border-radius: 6px;
  background: var(--color-bg-sunken);
}

.docs-list__meta {
  font-size: 12px;
  color: var(--color-text-muted);
  white-space: nowrap;
}
.edit-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
  gap: 0 var(--ys-space-4);
}

.edit-grid__wide {
  grid-column: 1 / -1;
}

.image-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-2);
}

/* 上传按钮 = 一个被样式化的 label，包着真正隐藏的 file input。
   不直接给 input 套按钮样式：各浏览器对 file 控件内部结构的可控程度不同，
   label 转发点击则是标准行为，稳定且可键盘聚焦 */
.image-input {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  width: 100%;
}

.image-input__upload {
  flex: none;
  cursor: pointer;
}

.image-input__upload input {
  display: none;
}

.image-row__thumb {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.image-row__thumb--blank {
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
  font-size: var(--ys-font-xs);
}

.spec-row {
  display: flex;
  align-items: center;
  gap: var(--ys-space-2);
  margin-bottom: var(--ys-space-2);
}

.spec-row__name {
  flex: 0 0 180px;
}

.spec-row__values {
  flex: 1 1 auto;
}

.spec-actions {
  display: flex;
  gap: var(--ys-space-2);
  margin-top: var(--ys-space-2);
}

.sku-warning {
  margin-bottom: var(--ys-space-3);
}

.edit-foot {
  display: flex;
  justify-content: flex-end;
  gap: var(--ys-space-2);
  padding-block: var(--ys-space-4);
}
</style>
