package com.warmpaw.application;

import static com.warmpaw.common.Json.*;

import com.warmpaw.common.Input;
import com.warmpaw.repository.BusinessRepository;
import com.warmpaw.service.AuthService;
import com.warmpaw.service.CatalogService;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/** 宠物与门店用例：区分公开展示和后台编辑，个体校验由 CatalogService 执行。 */
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

  public OperationResult home(Map<String, Object> query) {
    new Input(query, "");
    return new OperationResult(200, catalog.home());
  }

  public OperationResult shop() {
    return new OperationResult(200, catalog.publicShop());
  }

  public OperationResult categories() {
    return new OperationResult(200, catalog.categories());
  }

  public OperationResult pets(Map<String, Object> query) {
    return new OperationResult(200, catalog.pets(query, false));
  }

  public OperationResult pet(String petId) {
    return new OperationResult(200, catalog.publicPet(petId));
  }

  public OperationResult adminPets(Map<String, Object> query) {
    return new OperationResult(200, catalog.pets(query, true));
  }

  public OperationResult adminPet(String petId) {
    return new OperationResult(200, catalog.adminPet(store.get("pet", petId)));
  }

  public OperationResult createPet(Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(201, catalog.writePet(null, body, actor.id()));
  }

  public OperationResult updatePet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.writePet(petId, body, actor.id()));
  }

  public OperationResult publishPet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.publish(petId, body, actor.id(), true));
  }

  public OperationResult unpublishPet(
      String petId, Map<String, Object> body, AuthService.Actor actor) {
    return new OperationResult(200, catalog.publish(petId, body, actor.id(), false));
  }

  public OperationResult adminShop() {
    return new OperationResult(
        200,
        CatalogService.select(
            catalog.shop(),
            "id,name,address,latitude,longitude,coordinateSystem,phone,wechat,businessHours,pickupInstructions,version,banners,paymentTimeoutMinutes,pickupRetentionHours,exchangeEnabled"));
  }

  public OperationResult updateShop(Map<String, Object> body, AuthService.Actor actor) {
    catalog.updateShop(body, actor.id());
    return adminShop();
  }
}
