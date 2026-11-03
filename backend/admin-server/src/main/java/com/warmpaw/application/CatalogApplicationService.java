package com.warmpaw.application;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * 宠物与门店用例编排层。
 *
 * <p>核心职责是<b>区分"公开展示"与"后台编辑"两套视图</b>：
 *
 * <ul>
 *   <li>公开接口走 {@code catalog.publicShop()} / {@code publicPet()} / {@code pets(q, false)}，
 *       由 {@code CatalogReader} 做字段裁剪，不会下发 {@code ownerId}、成本、审核备注等内部字段；
 *   <li>后台接口走 {@code adminPet()} / {@code pets(q, true)}，包含审核状态与操作所需字段，
 *       但会去掉 {@code ownerId}（运营不需要知道录入人）。
 * </ul>
 *
 * <p>个体校验（字段格式、图片归属、检疫有效性、上下架前置条件）全部委托
 * {@link CatalogService}，本层不重复实现——否则两处规则会漂移。
 *
 * <p>事务为 {@code MANDATORY}，由外层请求执行器统一开启并持锁。
 */
@Service
@org.springframework.transaction.annotation.Transactional(
    propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
public class CatalogApplicationService {
  private final BusinessRepository store;
  private final CatalogService catalog;

  public CatalogApplicationService(BusinessRepository store, CatalogService catalog) {
    this.store = store;
    this.catalog = catalog;
  }

  /** 首页聚合（门店 + 轮播 + 推荐宠物 + 分类）。不接受任何查询参数，传了额外参数会被 400。 */
  public OperationResult home(Map<String, Object> query) {
    new Input(query, "");
    return new OperationResult(200, catalog.home());
  }

  /** 公开的门店信息：只含展示所需字段，不含支付时限、换宠开关等运营配置。 */
  public OperationResult shop() {
    return new OperationResult(200, catalog.publicShop());
  }

  /** 分类与品种字典，供筛选器渲染。 */
  public OperationResult categories() {
    return new OperationResult(200, catalog.categories());
  }

  /** 买家侧宠物列表：只返回可公开展示的状态，草稿与下架不会出现。 */
  public OperationResult pets(Map<String, Object> query) {
    return new OperationResult(200, catalog.pets(query, false));
  }

  /**
   * 买家侧宠物详情。
   *
   * <p>只有 {@code off}（草稿/下架）返回 404；{@code sold}/{@code reserved} 仍可查看，
   * 但会通过 {@code purchasable=false} 与阻断原因提示"当前不可预约"。
   * 这样做是为了避免买家点过的链接突然变成 404，同时仍能引导到相似推荐。
   */
  public OperationResult pet(String petId) {
    return new OperationResult(200, catalog.publicPet(petId));
  }

  /** 后台宠物列表：包含上下架状态、审核时间等运营字段。 */
  public OperationResult adminPets(Map<String, Object> query) {
    return new OperationResult(200, catalog.pets(query, true));
  }

  /** 后台宠物详情：含完整检疫材料与审核备注，供店长判断能否上架。 */
  public OperationResult adminPet(String petId) {
    return new OperationResult(200, catalog.adminPet(store.get("pet", petId)));
  }

  /** 新建宠物档案，状态固定从 {@code off} 开始，必须单独走一次上架审核。 */
  public OperationResult createPet(Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(201, catalog.writePet(null, body, actor.id()));
  }

  /**
   * 更新宠物档案。
   *
   * <p>若改动了健康相关信息，{@link CatalogService#writePet} 会把状态自动打回 {@code off}，
   * 因此这个接口的响应里 {@code status} 可能出乎调用方意料——前端应以其返回值为准重新渲染。
   */
  public OperationResult updatePet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.writePet(petId, body, actor.id()));
  }

  /** 上架：需要健康承诺、检疫核验、无库存占用、无未结售后，详见 {@link CatalogService#publish}。 */
  public OperationResult publishPet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.publish(petId, body, actor.id(), true));
  }

  /** 下架：只需原因，随时可执行（即使宠物正被订单占用，下架后不影响已成交订单的履约）。 */
  public OperationResult unpublishPet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.publish(petId, body, actor.id(), false));
  }

  /**
   * 后台门店配置。
   *
   * <p>与公开 {@code shop()} 的差别在于<b>包含 {@code version}</b>——前端提交更新时必须回传它，
   * 否则乐观锁无法生效，两个人同时编辑会互相覆盖。还包括支付超时、自提保留、换宠开关等交易配置。
   */
  public OperationResult adminShop() {
    return new OperationResult(
        200,
        CatalogService.select(
            catalog.shop(),
            "id,name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled"));
  }

  /** 更新门店配置，成功后直接返回最新后台视图，省掉前端再发一次 GET。 */
  public OperationResult updateShop(Map<String, Object> body, AuthService.Actor actor) {
    catalog.updateShop(body, actor.id());
    return adminShop();
  }
}
