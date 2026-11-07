<template>
  <div class="container page not-found">
    <div>
      <h1>这一页走丢了。</h1>
      <p class="lead">链接可能写错了，或者那只小家伙已经被接回家。</p>
      <div class="actions">
        <RouterLink class="btn" to="/pets">看看店里现在有谁</RouterLink
        ><RouterLink class="btn secondary" to="/">回到首页</RouterLink>
      </div>
    </div>
    <img src="/images/pets/corgi-sit.jpg" alt="" aria-hidden="true" />
  </div>
</template>
<style scoped>
.not-found {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 48px;
  padding-block: clamp(48px, 8vw, 120px);
}
.not-found h1 {
  font-size: clamp(44px, 6.4vw, 96px);
  line-height: 1.04;
  letter-spacing: -0.02em;
  margin-bottom: 20px;
}
.not-found img {
  width: clamp(200px, 28vw, 380px);
  aspect-ratio: 1;
  object-fit: cover;
  object-position: 70% 50%;
  border-radius: 50%;
}
@media (max-width: 760px) {
  .not-found {
    grid-template-columns: 1fr;
  }
  .not-found img {
    order: -1;
    width: 160px;
  }
}
</style>
