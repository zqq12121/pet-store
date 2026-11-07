<script setup lang="ts">
import { ref, computed, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { auth, buyer, date } from '../../../shared/api';
import { communityCategories, categoryLabel } from '../../../shared/communityTypes';
import type { CommunityPage } from '../../../shared/communityTypes';
import { useLoad } from '../../../shared/useLoad';
import LoadState from '../components/LoadState.vue';
import CommunityNoticeLink from '../components/CommunityNoticeLink.vue';
import PageHead from '../components/PageHead.vue';
const route = useRoute(),
  router = useRouter(),
  result = ref<CommunityPage>(),
  query = ref(String(route.query.query || '')),
  { loading, error, run } = useLoad();
const type = computed(() => (route.query.type === 'review' ? 'review' : 'post')),
  scope = computed(() => String(route.query.scope || 'all')),
  category = computed(() => String(route.query.category || '')),
  page = computed(() => Number(route.query.page || 1));
const load = () =>
  run(async () => {
    result.value = await buyer('GET', '/community/contents', undefined, {
      type: type.value,
      scope: scope.value,
      page: page.value,
      ...(category.value ? { category: category.value } : {}),
      ...(route.query.query ? { query: route.query.query } : {}),
    });
  });
watch(
  () => route.fullPath,
  () => {
    query.value = String(route.query.query || '');
    void load();
  },
  { immediate: true }
);
function filter(values: Record<string, string | undefined>) {
  return router.push({ path: '/community', query: { ...route.query, page: undefined, ...values } });
}
function personal(value: string) {
  if (value !== 'all' && !auth('buyer'))
    return router.push({
      path: '/login',
      query: { redirect: `/community?type=${type.value}&scope=${value}` },
    });
  return filter({ scope: value });
}
</script>
<template>
  <PageHead
    title="宠友社区"
    lead="接回家以后的日子，都可以在这里聊：养宠心得、晒日常、求助，还有到店后的评价。"
    :photos="[
      '/images/pets/border-collie-lavender.jpg',
      '/images/pets/golden-ball.jpg',
      '/images/pets/siamese-sofa.jpg',
    ]"
  >
    <div class="actions community-head-actions">
      <RouterLink class="btn" :to="{ path: '/community/new', query: { type } }">{{
        type === 'post' ? '发布帖子' : '评价店铺'
      }}</RouterLink
      ><CommunityNoticeLink />
    </div>
  </PageHead>
  <div class="container page community-page">
    <div class="tabs community-tabs" aria-label="社区板块">
      <button
        :class="{ active: type === 'post' }"
        @click="filter({ type: 'post', category: undefined, query: undefined, scope: 'all' })"
      >
        养宠交流</button
      ><button
        :class="{ active: type === 'review' }"
        @click="filter({ type: 'review', category: undefined, query: undefined, scope: 'all' })"
      >
        店铺评价
      </button>
    </div>
    <div class="actions community-filters">
      <button class="text-link" :aria-pressed="scope === 'all'" @click="personal('all')">
        全部{{ type === 'post' ? '帖子' : '评价' }}</button
      ><button class="text-link" :aria-pressed="scope === 'mine'" @click="personal('mine')">
        我的{{ type === 'post' ? '帖子' : '评价' }}</button
      ><button
        v-if="type === 'post'"
        class="text-link"
        :aria-pressed="scope === 'favorites'"
        @click="personal('favorites')"
      >
        我的收藏
      </button>
    </div>
    <template v-if="type === 'post'"
      ><form
        class="community-search"
        @submit.prevent="filter({ query: query.trim() || undefined })"
      >
        <label
          >搜索标题或正文<input
            v-model="query"
            maxlength="160"
            placeholder="搜索养宠心得、日常或问题" /></label
        ><button class="btn secondary">搜索</button>
      </form>
      <div class="tabs community-categories" aria-label="帖子分类">
        <button :class="{ active: !category }" @click="filter({ category: undefined })">
          全部分类</button
        ><button
          v-for="item in communityCategories"
          :key="item.value"
          :class="{ active: category === item.value }"
          @click="filter({ category: item.value })"
        >
          {{ item.label }}
        </button>
      </div></template
    >
    <p v-else class="notice">完成到店交付后可评价，每个订单只能评价一次。</p>
    <LoadState :loading="loading" :error="error" @retry="load" />
    <template v-if="result && !loading && !error"
      ><p v-if="!result.items.length" class="panel muted">
        {{ scope === 'favorites' ? '还没有收藏的帖子。' : '这里还没有符合条件的内容。' }}
      </p>
      <div class="community-list">
        <RouterLink
          v-for="item in result.items"
          :key="item.id"
          :to="`/community/${item.id}`"
          class="panel community-card"
          ><div v-if="item.images.length && item.status === 'published'" class="community-cover">
            <img :src="item.images[0].url" alt="社区内容配图" loading="lazy" /><span
              v-if="item.images.length > 1"
              >共 {{ item.images.length }} 张</span
            >
          </div>
          <div class="between">
            <small class="muted">{{ item.authorName }} · {{ date(item.createdAt) }}</small
            ><span v-if="item.status === 'hidden'" class="status">已隐藏</span
            ><span v-else-if="item.type === 'post'" class="status">{{
              categoryLabel(item.category!)
            }}</span
            ><span v-else class="community-rating"
              >{{ '★'.repeat(item.rating!) }}{{ '☆'.repeat(5 - item.rating!) }}
              {{ item.rating }}分</span
            >
          </div>
          <h2 v-if="item.title">{{ item.title }}</h2>
          <p>{{ item.body.length > 180 ? item.body.slice(0, 180) + '…' : item.body }}</p>
          <small v-if="item.type === 'post'" class="muted"
            >{{ item.likeCount }} 人点赞{{ item.favorited ? ' · 已收藏' : '' }}</small
          ><small v-else class="muted">已完成到店消费</small></RouterLink
        >
      </div>
      <div v-if="result.total > 20" class="between community-pagination">
        <button
          class="btn secondary"
          :disabled="page <= 1"
          @click="router.push({ query: { ...route.query, page: page - 1 } })"
        >
          上一页</button
        ><span>第 {{ page }} 页 · 共 {{ result.total }} 条</span
        ><button
          class="btn secondary"
          :disabled="page * 20 >= result.total"
          @click="router.push({ query: { ...route.query, page: page + 1 } })"
        >
          下一页
        </button>
      </div></template
    >
  </div>
</template>
<style scoped>
.community-head-actions {
  margin-top: 26px;
}
.community-tabs {
  margin-bottom: 14px;
}
.community-filters {
  gap: 24px;
  margin-bottom: 8px;
}
.community-filters [aria-pressed='true'] {
  font-weight: 700;
  text-decoration: underline;
  text-underline-offset: 5px;
}
.community-search {
  display: flex;
  gap: 12px;
  align-items: end;
  max-width: 720px;
  margin: 12px 0 16px;
}
.community-search label {
  flex: 1;
}
.community-categories {
  margin-bottom: 28px;
  flex-wrap: wrap;
}
/* 两列瀑布流：有图的卡片更高，错落排列 */
.community-list {
  columns: 2;
  column-gap: 24px;
}
.community-card {
  display: block;
  break-inside: avoid;
  margin-bottom: 24px;
  padding: 0 0 24px;
  overflow: hidden;
  transition: border-color 0.2s;
  border: 1px solid var(--line);
}
.community-card > :not(.community-cover) {
  margin-inline: 26px;
}
.community-card > .between {
  margin-top: 22px;
}
.community-card:hover {
  border-color: var(--brand);
}
.community-cover {
  position: relative;
}
.community-cover img {
  width: 100%;
  aspect-ratio: 4 / 3;
  object-fit: cover;
}
.community-cover span {
  position: absolute;
  right: 14px;
  bottom: 14px;
  padding: 3px 12px;
  border-radius: 999px;
  background: #3d2f22b3;
  color: #fff;
  font-size: 12px;
}
.community-card h2 {
  overflow-wrap: anywhere;
  font-family: var(--heavy);
  font-weight: 800;
  font-size: 24px;
  line-height: 1.3;
  margin: 12px 0 6px;
}
.community-card p {
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  margin-top: 10px;
}
.community-card .between {
  flex-wrap: wrap;
}
.community-rating {
  color: var(--accent);
  white-space: nowrap;
}
.community-pagination {
  margin-top: 28px;
}
@media (max-width: 760px) {
  .community-head-actions {
    margin-top: 18px;
  }
  .community-list {
    columns: 1;
  }
  .community-card > :not(.community-cover) {
    margin-inline: 20px;
  }
  .community-card h2 {
    font-size: 20px;
  }
  .community-categories {
    gap: 4px;
  }
  .community-categories button {
    padding: 10px 12px;
  }
  .community-pagination {
    font-size: 12px;
  }
}
</style>
