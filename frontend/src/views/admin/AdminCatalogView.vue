<script setup lang="ts">
/**
 * 类目、参数模板与品牌。
 *
 * 三者放在一页不是图省事：它们是一条链 —— 类目决定商品归到哪，参数模板挂在类目上
 * 决定商品要填哪些参数，品牌是商品的另一个筛选维度。运营调整其中一个时几乎总要顺手看另两个，
 * 拆成三个页面只会让人来回跳。
 *
 * 删除一律不做前端规则判断。类目删除有「下级节点 / 在售商品 / 参数模板引用」三条规则，
 * 品牌删除有「在售商品引用」的规则，把它们抄一份到前端只会得到两份迟早不一致的规则 ——
 * 后端拒绝时的原因已经足够具体，直接展示就行。
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Delete, Edit, Plus } from '@element-plus/icons-vue'
import {
  createAttribute,
  createBrand,
  createCategory,
  deleteAttribute,
  deleteBrand,
  deleteCategory,
  listAttributes,
  listBrands,
  listCategoryTree,
  updateAttribute,
  updateBrand,
  updateCategory,
} from '@/api/admin/catalog'
import ErrorState from '@/components/ui/ErrorState.vue'
import type {
  AdminAttribute,
  AdminBrand,
  AttributeUpsertRequest,
  BrandUpsertRequest,
  CategoryUpsertRequest,
} from '@/types/admin'
import type { CategoryNode } from '@/types/models'

const loading = ref(true)
const loadError = ref<string | null>(null)

const categoryTree = ref<CategoryNode[]>([])
const brands = ref<AdminBrand[]>([])
const attributes = ref<AdminAttribute[]>([])
const attributesLoading = ref(false)
const selectedCategoryId = ref<number | null>(null)

const selectedCategory = computed(() => findNode(categoryTree.value, selectedCategoryId.value))

onMounted(reloadAll)

async function reloadAll() {
  loading.value = true
  loadError.value = null
  try {
    await Promise.all([reloadCategories(), reloadBrands()])
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

async function reloadCategories() {
  categoryTree.value = await listCategoryTree()
}

async function reloadBrands() {
  brands.value = await listBrands()
}

async function selectCategory(node: CategoryNode) {
  selectedCategoryId.value = node.id
  await reloadAttributes()
}

/**
 * 请求序号：点类目的速度可以比网络快，晚发出的先回来就会把先发出的结果盖掉 ——
 * 表现是「选中 A 类目，右侧列的是 B 类目的参数」，而两边都不报错。
 * 只认最后一次发出的那个请求，回来的结果序号对不上就直接丢掉。
 */
let attributesSeq = 0

async function reloadAttributes() {
  const categoryId = selectedCategoryId.value
  if (categoryId === null) {
    attributesSeq += 1
    attributes.value = []
    return
  }
  const seq = ++attributesSeq
  attributesLoading.value = true
  try {
    const list = await listAttributes(categoryId)
    if (seq === attributesSeq) {
      attributes.value = list
    }
  } catch {
    // 拦截器已提示；这里退回空列表，不把整页判死
    if (seq === attributesSeq) {
      attributes.value = []
    }
  } finally {
    if (seq === attributesSeq) {
      attributesLoading.value = false
    }
  }
}

function findNode(nodes: CategoryNode[], id: number | null): CategoryNode | null {
  if (id === null) {
    return null
  }
  for (const node of nodes) {
    if (node.id === id) {
      return node
    }
    const hit = findNode(node.children ?? [], id)
    if (hit) {
      return hit
    }
  }
  return null
}

// ==================== 类目 ====================

interface CategoryDraft extends CategoryUpsertRequest {
  id: number | null
}

const categoryDialog = reactive({ visible: false, title: '', saving: false })
const categoryForm = ref<CategoryDraft>(emptyCategory())

function emptyCategory(): CategoryDraft {
  return { id: null, parentId: 0, name: '', sort: 0, status: 1 }
}

function openCreateCategory(parent: CategoryNode | null) {
  categoryForm.value = { ...emptyCategory(), parentId: parent?.id ?? 0 }
  categoryDialog.title = parent ? `在「${parent.name}」下新建子类目` : '新建一级类目'
  categoryDialog.visible = true
}

function openEditCategory(node: CategoryNode) {
  categoryForm.value = {
    id: node.id,
    // 编辑时不改挂载点：换父类目会让它下面的商品一起换位置，那是一个独立且危险的操作。
    // 所以这里必须回填**接口给的** parentId，而不是从树上的位置推。
    // 两者眼下在管理台是同一个值 —— 管理树查的是全量节点，停用的父类目也在，子节点照常挂在
    // 它下面。但那是「那个查询带不带状态过滤」的结果，不是模型给的保证：公开树
    // `GET /categories/tree` 按 status=1 过滤，父类目停用后子节点就被挂到根上了
    //（见后端 CategoryTreeAssembler 的 parent == null 分支）。写死 0、或者哪天照着树的形状算，
    // 都等于替运营做了「挪到根」的决定，而对话框里根本没有这个选项，
    // 改一次名字就整棵子树换位置，事后只能从商品全跑错了地方倒推回来
    parentId: node.parentId ?? 0,
    name: node.name,
    sort: node.sort ?? 0,
    status: node.status ?? 1,
  }
  categoryDialog.title = `编辑类目「${node.name}」`
  categoryDialog.visible = true
}

async function submitCategory() {
  const draft = categoryForm.value
  if (!draft.name.trim()) {
    ElMessage.warning('类目名不能为空')
    return
  }
  categoryDialog.saving = true
  try {
    if (draft.id === null) {
      await createCategory({
        parentId: draft.parentId,
        name: draft.name.trim(),
        sort: draft.sort,
        status: draft.status,
      })
      ElMessage.success('类目已创建')
    } else {
      await updateCategory(draft.id, {
        parentId: draft.parentId,
        name: draft.name.trim(),
        sort: draft.sort,
        status: draft.status,
      })
      ElMessage.success('已保存')
    }
    categoryDialog.visible = false
    await reloadCategories()
  } catch {
    // 拒绝原因由后端给出，拦截器已展示；对话框留着让运营改
  } finally {
    categoryDialog.saving = false
  }
}

async function removeCategory(node: CategoryNode) {
  try {
    await ElMessageBox.confirm(
      `确定删除类目「${node.name}」？有下级类目、在售商品或参数模板引用它时会被拒绝。`,
      '删除类目',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteCategory(node.id)
    ElMessage.success('已删除')
    if (selectedCategoryId.value === node.id) {
      selectedCategoryId.value = null
      attributes.value = []
    }
    await reloadCategories()
  } catch {
    // 同删除品牌
  }
}

// ==================== 参数模板 ====================

const attributeDialog = reactive({ visible: false, title: '', saving: false })
const attributeForm = ref<AttributeUpsertRequest & { id: number | null }>(emptyAttribute())

function emptyAttribute() {
  return { id: null, name: '', inputType: 'text', unit: '', sort: 0 }
}

function openCreateAttribute() {
  if (selectedCategoryId.value === null) {
    return
  }
  attributeForm.value = emptyAttribute()
  attributeDialog.title = `给「${selectedCategory.value?.name ?? ''}」加一个参数`
  attributeDialog.visible = true
}

function openEditAttribute(item: AdminAttribute) {
  attributeForm.value = {
    id: item.id,
    name: item.name,
    inputType: item.inputType ?? 'text',
    unit: item.unit ?? '',
    sort: item.sort ?? 0,
  }
  attributeDialog.title = `编辑参数「${item.name}」`
  attributeDialog.visible = true
}

async function submitAttribute() {
  const draft = attributeForm.value
  if (selectedCategoryId.value === null) {
    return
  }
  if (!draft.name.trim()) {
    ElMessage.warning('参数名不能为空')
    return
  }
  const payload: AttributeUpsertRequest = {
    name: draft.name.trim(),
    inputType: draft.inputType || 'text',
    unit: draft.unit?.trim() || undefined,
    sort: draft.sort ?? 0,
  }
  attributeDialog.saving = true
  try {
    if (draft.id === null) {
      await createAttribute(selectedCategoryId.value, payload)
      ElMessage.success('参数已添加')
    } else {
      await updateAttribute(draft.id, payload)
      ElMessage.success('已保存')
    }
    attributeDialog.visible = false
    await reloadAttributes()
  } catch {
    // 同类别目
  } finally {
    attributeDialog.saving = false
  }
}

async function removeAttribute(item: AdminAttribute) {
  try {
    await ElMessageBox.confirm(
      `确定删除参数「${item.name}」？已有商品填过这个参数时会被拒绝删除 —— ` +
        '那些值不会跟着消失，删掉定义只会让它们变成一堆找不到出处的数据。',
      '删除参数',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteAttribute(item.id)
    ElMessage.success('已删除')
    await reloadAttributes()
  } catch {
    // 同删除类目
  }
}

const inputTypeOptions = [
  { label: '文本', value: 'text' },
  { label: '数字', value: 'number' },
  { label: '枚举', value: 'select' },
]

// ==================== 品牌 ====================

const brandDialog = reactive({ visible: false, title: '', saving: false })
const brandForm = ref<BrandUpsertRequest & { id: number | null }>(emptyBrand())

function emptyBrand() {
  return { id: null, name: '', logo: '', description: '', status: 1 }
}

function openCreateBrand() {
  brandForm.value = emptyBrand()
  brandDialog.title = '新建品牌'
  brandDialog.visible = true
}

function openEditBrand(brand: AdminBrand) {
  brandForm.value = {
    id: brand.id,
    name: brand.name,
    logo: brand.logo ?? '',
    description: brand.description ?? '',
    status: brand.status ?? 1,
  }
  brandDialog.title = `编辑品牌「${brand.name}」`
  brandDialog.visible = true
}

async function submitBrand() {
  const draft = brandForm.value
  if (!draft.name.trim()) {
    ElMessage.warning('品牌名不能为空')
    return
  }
  const payload: BrandUpsertRequest = {
    name: draft.name.trim(),
    logo: draft.logo?.trim() || undefined,
    description: draft.description?.trim() || undefined,
    status: draft.status,
  }
  brandDialog.saving = true
  try {
    if (draft.id === null) {
      await createBrand(payload)
      ElMessage.success('品牌已创建')
    } else {
      await updateBrand(draft.id, payload)
      ElMessage.success('已保存')
    }
    brandDialog.visible = false
    await reloadBrands()
  } catch {
    // 同类别目
  } finally {
    brandDialog.saving = false
  }
}

async function removeBrand(brand: AdminBrand) {
  try {
    await ElMessageBox.confirm(
      `确定删除品牌「${brand.name}」？仍有在售商品挂着这个品牌时会被拒绝，` +
        '此时请先把那些商品改到别的品牌或置为「无品牌」。',
      '删除品牌',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' },
    )
  } catch {
    return
  }
  try {
    await deleteBrand(brand.id)
    ElMessage.success('已删除')
    await reloadBrands()
  } catch {
    // 同删除类目
  }
}
</script>

<template>
  <div class="admin-panel">
    <div class="admin-toolbar">
      <h2 class="admin-toolbar__title">类目与品牌</h2>
      <span class="admin-toolbar__count"
        >{{ categoryTree.length }} 个一级类目 · {{ brands.length }} 个品牌</span
      >
    </div>

    <ErrorState v-if="loadError" :message="loadError" :on-retry="reloadAll" />

    <el-skeleton v-else-if="loading" :rows="8" animated />

    <div v-else class="catalog-grid">
      <section class="admin-detail">
        <h3 class="admin-section__title">
          类目树
          <el-button :icon="Plus" text type="primary" @click="openCreateCategory(null)"
            >一级类目</el-button
          >
        </h3>
        <el-tree
          :data="categoryTree"
          node-key="id"
          :props="{ label: 'name', children: 'children' }"
          default-expand-all
          highlight-current
          :expand-on-click-node="false"
          @node-click="selectCategory"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <span class="tree-node__name">
                {{ data.name }}
                <el-tag v-if="data.status === 0" type="info" effect="plain" size="small"
                  >停用</el-tag
                >
              </span>
              <span class="tree-node__actions" @click.stop>
                <!--
                  三个按钮都只有图标，没有可读的文字。`title` 只是鼠标悬停提示，
                  读屏软件念出来的会是「按钮」两个字；同一棵树上有几十个同名按钮时，
                  「编辑」也说明不了编辑的是哪一条，所以名字里带上类目名
                -->
                <el-button
                  :icon="Plus"
                  text
                  size="small"
                  title="新建子类目"
                  :aria-label="`在「${data.name}」下新建子类目`"
                  @click="openCreateCategory(data)"
                />
                <el-button
                  :icon="Edit"
                  text
                  size="small"
                  title="编辑"
                  :aria-label="`编辑类目「${data.name}」`"
                  @click="openEditCategory(data)"
                />
                <el-button
                  :icon="Delete"
                  text
                  size="small"
                  type="danger"
                  title="删除"
                  :aria-label="`删除类目「${data.name}」`"
                  @click="removeCategory(data)"
                />
              </span>
            </div>
          </template>
        </el-tree>
        <p v-if="categoryTree.length === 0" class="admin-empty">还没有类目</p>
      </section>

      <section class="admin-detail">
        <h3 class="admin-section__title">
          参数模板
          <el-button
            :icon="Plus"
            text
            type="primary"
            :disabled="selectedCategoryId === null"
            @click="openCreateAttribute"
          >
            添加参数
          </el-button>
        </h3>

        <p v-if="selectedCategoryId === null" class="admin-empty">
          在左边点一个类目，看它的参数模板
        </p>

        <template v-else>
          <p class="admin-dialog__hint">
            模板挂在类目上，商品只填值。列在这里的参数会出现在该类目下每个商品的编辑页里。
          </p>
          <div class="admin-table">
            <el-table v-loading="attributesLoading" :data="attributes" style="width: 100%">
              <el-table-column prop="name" label="参数名" min-width="120">
                <template #default="{ row }">
                  <span class="admin-cell--strong">{{ row.name }}</span>
                </template>
              </el-table-column>
              <el-table-column label="单位" width="80">
                <template #default="{ row }">
                  <span class="admin-cell--muted">{{ row.unit ?? '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="控件" width="80">
                <template #default="{ row }">
                  <span class="admin-cell--tiny">{{
                    inputTypeOptions.find((t) => t.value === row.inputType)?.label ?? row.inputType
                  }}</span>
                </template>
              </el-table-column>
              <el-table-column prop="sort" label="排序" width="70" align="right">
                <template #default="{ row }">
                  <span class="admin-cell--num">{{ row.sort }}</span>
                </template>
              </el-table-column>
              <el-table-column label="操作" width="130" fixed="right">
                <template #default="{ row }">
                  <div class="admin-table__actions">
                    <el-button link type="primary" @click="openEditAttribute(row)">编辑</el-button>
                    <el-button link type="danger" @click="removeAttribute(row)">删除</el-button>
                  </div>
                </template>
              </el-table-column>
              <template #empty>
                <p class="admin-empty">这个类目还没有参数模板</p>
              </template>
            </el-table>
          </div>
        </template>
      </section>

      <section class="admin-detail catalog-grid__brands">
        <h3 class="admin-section__title">
          品牌
          <el-button :icon="Plus" text type="primary" @click="openCreateBrand">新建品牌</el-button>
        </h3>
        <div class="admin-table">
          <el-table :data="brands" style="width: 100%">
            <el-table-column label="品牌" min-width="160">
              <template #default="{ row }">
                <div class="brand-cell">
                  <el-image class="brand-cell__logo" :src="row.logo ?? undefined" fit="contain">
                    <template #error>
                      <div class="brand-cell__logo brand-cell__logo--blank" aria-hidden="true">
                        —
                      </div>
                    </template>
                  </el-image>
                  <div class="admin-stack">
                    <span class="admin-cell--strong">{{ row.name }}</span>
                    <span v-if="row.description" class="admin-cell--muted admin-cell--ellipsis">
                      {{ row.description }}
                    </span>
                  </div>
                </div>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default="{ row }">
                <el-tag :type="row.status === 1 ? 'success' : 'info'" effect="plain" size="small">
                  {{ row.status === 1 ? '启用' : '停用' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" fixed="right">
              <template #default="{ row }">
                <div class="admin-table__actions">
                  <el-button link type="primary" @click="openEditBrand(row)">编辑</el-button>
                  <el-button link type="danger" @click="removeBrand(row)">删除</el-button>
                </div>
              </template>
            </el-table-column>
            <template #empty>
              <p class="admin-empty">还没有品牌</p>
            </template>
          </el-table>
        </div>
      </section>
    </div>

    <!-- 类目 -->
    <el-dialog v-model="categoryDialog.visible" :title="categoryDialog.title" width="440px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="categoryForm.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="categoryForm.sort"
            :min="0"
            :controls="false"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="categoryForm.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <p class="admin-dialog__hint">
        停用的类目不会出现在商城的类目导航里，但已经归在它下面的商品照常可买 ——
        「不摆了」和「下架了」是两件事。
      </p>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="categoryDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="categoryDialog.saving" @click="submitCategory"
            >保存</el-button
          >
        </div>
      </template>
    </el-dialog>

    <!-- 参数 -->
    <el-dialog v-model="attributeDialog.visible" :title="attributeDialog.title" width="440px">
      <el-form label-width="80px">
        <el-form-item label="参数名" required>
          <el-input
            v-model="attributeForm.name"
            maxlength="32"
            placeholder="如「净含量」「保质期」"
          />
        </el-form-item>
        <el-form-item label="单位">
          <el-input
            v-model="attributeForm.unit"
            maxlength="16"
            placeholder="如「克」「天」，没有就留空"
          />
        </el-form-item>
        <el-form-item label="控件">
          <el-select v-model="attributeForm.inputType" style="width: 100%">
            <el-option
              v-for="t in inputTypeOptions"
              :key="t.value"
              :label="t.label"
              :value="t.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="排序">
          <el-input-number
            v-model="attributeForm.sort"
            :min="0"
            :controls="false"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <p class="admin-dialog__hint">
        参数挂到哪个类目由左边选中的那个决定，建好之后不能再改挂 —— 要换就删了重建。
      </p>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="attributeDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="attributeDialog.saving" @click="submitAttribute"
            >保存</el-button
          >
        </div>
      </template>
    </el-dialog>

    <!-- 品牌 -->
    <el-dialog v-model="brandDialog.visible" :title="brandDialog.title" width="440px">
      <el-form label-width="80px">
        <el-form-item label="名称" required>
          <el-input v-model="brandForm.name" maxlength="32" />
        </el-form-item>
        <el-form-item label="Logo URL">
          <el-input v-model="brandForm.logo" placeholder="https://…" />
        </el-form-item>
        <el-form-item label="简介">
          <el-input
            v-model="brandForm.description"
            type="textarea"
            :rows="3"
            maxlength="200"
            show-word-limit
          />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="brandForm.status" style="width: 100%">
            <el-option label="启用" :value="1" />
            <el-option label="停用" :value="0" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <div class="admin-dialog__foot">
          <el-button @click="brandDialog.visible = false">取消</el-button>
          <el-button type="primary" :loading="brandDialog.saving" @click="submitBrand"
            >保存</el-button
          >
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.catalog-grid {
  display: grid;
  grid-template-columns: minmax(260px, 1fr) minmax(320px, 1.4fr);
  gap: var(--ys-space-4);
  align-items: start;
}

.catalog-grid__brands {
  grid-column: 1 / -1;
}

@media (max-width: 960px) {
  .catalog-grid {
    grid-template-columns: 1fr;
  }
}

.tree-node {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--ys-space-2);
  width: 100%;
  padding-inline-end: var(--ys-space-2);
}

.tree-node__name {
  display: inline-flex;
  align-items: center;
  gap: var(--ys-space-2);
}

.tree-node__actions {
  display: none;
  gap: 2px;
}

.tree-node:hover .tree-node__actions,
.tree-node:focus-within .tree-node__actions {
  display: inline-flex;
}

.brand-cell {
  display: flex;
  align-items: center;
  gap: var(--ys-space-3);
  min-width: 0;
}

.brand-cell__logo {
  width: 40px;
  height: 40px;
  flex: none;
  border-radius: var(--ys-radius-sm);
  background: var(--color-bg-sunken);
  overflow: hidden;
}

.brand-cell__logo--blank {
  display: grid;
  place-items: center;
  color: var(--color-text-muted);
}
</style>
