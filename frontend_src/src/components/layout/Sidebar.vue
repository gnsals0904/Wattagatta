<!-- components/layout/Sidebar.vue -->
<template>
  <Card>
    <!-- Logo -->
    <h1
      class="font-bold text-lg lg:text-3xl bg-gradient-to-br from-white via-white/50 to-transparent bg-clip-text text-yellow-500">
      WTGT
    </h1>
    <p class="text-slate-400 text-sm mb-2">Hello,</p>

    <!-- User Profile -->
    <a href="/user" class="hover:bg-white/10 transition-all block duration-150 ease-linear rounded-lg group w-full">
      <div class="py-3 px-2">
        <UserAvatar :image="'/user-profile.jpg'" :name="authStore.user?.nickname || 'User'" :description="authStore.user?.role || 'ADMIN'" />
      </div>
    </a>

    <hr class="my-2 border-slate-700" />

    <!-- Navigation -->
    <nav class="flex flex-col space-y-2 my-5">
      <template v-for="item in menuItems" :key="item.name">
        <router-link :to="item.path"
          class="hover:bg-white/10 transition duration-150 ease-linear rounded-lg py-3 px-2 group">
          <div class="relative flex items-center space-x-2">
            <Icons :name="item.icon" hover size="md" class="group-hover:text-yellow-200" />
            <div>
              <p class="font-bold text-base lg:text-lg text-slate-200 leading-4 group-hover:text-amber-200">
                {{ item.name }}
              </p>
            </div>
            <div v-if="item.badge"
              class="absolute -top-3 -right-3 md:top-0 md:right-0 px-2 py-1.5 rounded-full bg-amber-200 text-xs font-mono font-bold">
              {{ item.badge }}
            </div>
          </div>
        </router-link>
      </template>
    </nav>
    <!-- 로그아웃 버튼 -->
    <button @click="handleLogout" class="text-center text-gray-400 hover:text-white">Log Out</button>
  </Card>
</template>

<script setup>
import { ref } from "vue"
import Card from "../common/Card.vue"
import UserAvatar from "../common/UserAvatar.vue"
import Icons from "../common/Icons.vue"
import { useRouter } from "vue-router";
import { useAuthStore } from "../../stores/auth";

const router = useRouter()
const authStore = useAuthStore()

const handleLogout = () => {
  authStore.logout()
  router.push('/login')
}

const menuItems = ref([
  {
    name: "Simulator",
    icon: "HomeIcon",
    path: "/",
  },
  {
    name: "RC car",
    icon: "ShoppingCartIcon",
    path: "/rc_car",
  },
  {
    name: "Stock Monitoring",
    icon: "ComputerDesktopIcon",
    path: "/monitoring",
  },
  {
    name: "Order",
    icon: "ClipboardDocumentListIcon",
    path: "/order",
  },
  {
    name: "User",
    icon: "UserIcon",
    path: "/user",
  },
]);
</script>
