export interface ApiResponse<T> {
  code: number
  msg: string
  data: T
}

export interface UserProfile {
  id: string
  username: string
  nickname: string
  roleName: string
  avatar: string
}

export interface LoginResponse {
  token: string
  user: UserProfile
}

export interface Product {
  id: number
  name: string
  subtitle: string
  category: string
  brand: string
  price: number
  stock: number
  monthlySales: number
  image: string
  salesCopy: string
  description: string
  tags: string[]
}

export interface CartItem {
  id: number
  productId: number
  name: string
  image: string
  price: number
  quantity: number
  stock: number
  subtotal: number
}

export interface OrderItem {
  id: number
  productId: number
  productName: string
  productImage: string
  unitPrice: number
  quantity: number
  subtotal: number
}

export interface Order {
  id: number
  orderNo: string
  recipientName: string
  recipientPhone: string
  address: string
  totalAmount: number
  status: string
  createdAt: string
  items: OrderItem[]
}

export interface LogisticsStep {
  status: string
  detail: string
  time: string
}

export interface Logistics {
  orderId: number
  orderNo: string
  carrier: string
  trackingNo: string
  steps: LogisticsStep[]
}

export interface KnowledgeSnippet {
  title: string
  content: string
  scope: string
}

export interface ToolCall {
  tool: string
  input: string
  output: string
}

export interface ChatResponse {
  sessionId: string
  reply: string
  knowledge: KnowledgeSnippet[]
  toolCalls: ToolCall[]
  recommendedProducts: Product[]
}

export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  knowledge?: KnowledgeSnippet[]
  toolCalls?: ToolCall[]
  recommendedProducts?: Product[]
}
