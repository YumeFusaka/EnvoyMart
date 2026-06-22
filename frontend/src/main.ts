import { createApp } from 'vue'
import pinia from './stores'   

import App from './App.vue'
import router from './router'
import 'echarts' 

import 'element-plus/theme-chalk/src/index.scss'  

import VChart from "vue-echarts";

const app = createApp(App)

app.component('VChart', VChart)
app.use(pinia) 
app.use(router)

app.mount('#app')
