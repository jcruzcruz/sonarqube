/*
 * SonarQube
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.server.ui.ws;

import com.google.common.collect.Lists;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import org.sonar.api.i18n.I18n;
import org.sonar.api.resources.Qualifiers;
import org.sonar.api.resources.ResourceType;
import org.sonar.api.resources.ResourceTypes;
import org.sonar.api.server.ws.Request;
import org.sonar.api.server.ws.Response;
import org.sonar.api.server.ws.WebService.NewAction;
import org.sonar.api.server.ws.WebService.NewController;
import org.sonar.api.utils.DateUtils;
import org.sonar.api.utils.text.JsonWriter;
import org.sonar.api.web.NavigationSection;
import org.sonar.api.web.Page;
import org.sonar.db.DbClient;
import org.sonar.db.DbSession;
import org.sonar.db.component.ComponentDto;
import org.sonar.db.component.SnapshotDto;
import org.sonar.db.measure.MeasureDto;
import org.sonar.db.measure.MeasureQuery;
import org.sonar.db.property.PropertyDto;
import org.sonar.db.property.PropertyQuery;
import org.sonar.db.qualitygate.QualityGateDto;
import org.sonar.server.ce.ws.ActivityAction;
import org.sonar.server.component.ComponentFinder;
import org.sonar.server.exceptions.ForbiddenException;
import org.sonar.server.qualitygate.QualityGateFinder;
import org.sonar.server.qualityprofile.QPMeasureData;
import org.sonar.server.qualityprofile.QualityProfile;
import org.sonar.server.ui.ViewProxy;
import org.sonar.server.ui.Views;
import org.sonar.server.user.UserSession;

import static java.util.Locale.ENGLISH;
import static org.sonar.api.measures.CoreMetrics.QUALITY_PROFILES_KEY;
import static org.sonar.api.web.UserRole.ADMIN;
import static org.sonar.api.web.UserRole.USER;
import static org.sonar.core.permission.GlobalPermissions.QUALITY_PROFILE_ADMIN;
import static org.sonar.server.ws.KeyExamples.KEY_PROJECT_EXAMPLE_001;

public class ComponentNavigationAction implements NavigationWsAction {

  private static final String PARAM_COMPONENT_KEY = "componentKey";

  private static final String PROPERTY_COMPARABLE = "comparable";
  private static final String PROPERTY_CONFIGURABLE = "configurable";
  private static final String PROPERTY_HAS_ROLE_POLICY = "hasRolePolicy";
  private static final String PROPERTY_MODIFIABLE_HISTORY = "modifiable_history";
  private static final String PROPERTY_UPDATABLE_KEY = "updatable_key";

  private final DbClient dbClient;
  private final Views views;
  private final I18n i18n;
  private final ResourceTypes resourceTypes;
  private final UserSession userSession;
  private final ComponentFinder componentFinder;
  private final QualityGateFinder qualityGateFinder;

  public ComponentNavigationAction(DbClient dbClient, Views views, I18n i18n, ResourceTypes resourceTypes, UserSession userSession,
    ComponentFinder componentFinder, QualityGateFinder qualityGateFinder) {
    this.dbClient = dbClient;
    this.views = views;
    this.i18n = i18n;
    this.resourceTypes = resourceTypes;
    this.userSession = userSession;
    this.componentFinder = componentFinder;
    this.qualityGateFinder = qualityGateFinder;
  }

  @Override
  public void define(NewController context) {
    NewAction projectNavigation = context.createAction("component")
      .setDescription("Get information concerning component navigation for the current user. " +
        "Requires the 'Browse' permission on the component's project.")
      .setHandler(this)
      .setInternal(true)
      .setResponseExample(getClass().getResource("example-component.json"))
      .setSince("5.2");

    projectNavigation.createParam(PARAM_COMPONENT_KEY)
      .setDescription("A component key.")
      .setExampleValue(KEY_PROJECT_EXAMPLE_001);
  }

  @Override
  public void handle(Request request, Response response) throws Exception {
    String componentKey = request.mandatoryParam(PARAM_COMPONENT_KEY);
    try (DbSession session = dbClient.openSession(false)) {
      ComponentDto component = componentFinder.getByKey(session, componentKey);
      if (!(userSession.hasComponentUuidPermission(USER, component.projectUuid()) || userSession.hasComponentUuidPermission(ADMIN, component.projectUuid()))) {
        throw new ForbiddenException("Insufficient privileges");
      }
      Optional<SnapshotDto> analysis = dbClient.snapshotDao().selectLastAnalysisByRootComponentUuid(session, component.projectUuid());

      JsonWriter json = response.newJsonWriter();
      json.beginObject();
      writeComponent(json, session, component, analysis.orElse(null));
      writeProfiles(json, session, component);
      writeQualityGate(json, session, component);
      if (userSession.hasComponentUuidPermission(ADMIN, component.projectUuid()) || userSession.hasPermission(QUALITY_PROFILE_ADMIN)) {
        writeConfiguration(json, component);
      }
      writeBreadCrumbs(json, session, component);
      json.endObject().close();
    }
  }

  private void writeComponent(JsonWriter json, DbSession session, ComponentDto component, @Nullable SnapshotDto analysis) {
    json.prop("key", component.key())
      .prop("uuid", component.uuid())
      .prop("name", component.name())
      .prop("description", component.description())
      .prop("isComparable", componentTypeHasProperty(component, PROPERTY_COMPARABLE))
      .prop("isFavorite", isFavourite(session, component));

    if (analysis != null) {
      json.prop("version", analysis.getVersion())
        .prop("snapshotDate", DateUtils.formatDateTime(new Date(analysis.getCreatedAt())));
      List<ViewProxy<Page>> pages = views.getPages(NavigationSection.RESOURCE, component.scope(), component.qualifier(), component.language());
      writeExtensions(json, component, pages);
    }
  }

  private boolean isFavourite(DbSession session, ComponentDto component) {
    PropertyQuery propertyQuery = PropertyQuery.builder()
      .setUserId(userSession.getUserId())
      .setKey("favourite")
      .setComponentId(component.getId())
      .build();
    List<PropertyDto> componentFavourites = dbClient.propertiesDao().selectByQuery(propertyQuery, session);
    return componentFavourites.size() == 1;
  }

  private void writeProfiles(JsonWriter json, DbSession session, ComponentDto component) {
    json.name("qualityProfiles").beginArray();
    dbClient.measureDao().selectSingle(session, MeasureQuery.builder().setComponentUuid(component.projectUuid()).setMetricKey(QUALITY_PROFILES_KEY).build())
      .ifPresent(dbMeasure -> Stream.of(dbMeasure)
        .flatMap(toQualityProfiles())
        .forEach(writeToJson(json)));
    json.endArray();
  }

  private static Consumer<QualityProfile> writeToJson(JsonWriter json) {
    return profile -> json.beginObject()
      .prop("key", profile.getQpKey())
      .prop("name", profile.getQpName())
      .prop("language", profile.getLanguageKey())
      .endObject();
  }

  private static Function<MeasureDto, Stream<QualityProfile>> toQualityProfiles() {
    return dbMeasure -> QPMeasureData.fromJson(dbMeasure.getData()).getProfiles().stream();
  }

  private void writeQualityGate(JsonWriter json, DbSession session, ComponentDto component) {
    Optional<QualityGateFinder.QualityGateData> qualityGateData = qualityGateFinder.getQualityGate(session, component.getId());
    if (!qualityGateData.isPresent()) {
      return;
    }
    QualityGateDto qualityGateDto = qualityGateData.get().getQualityGate();
    json.name("qualityGate").beginObject()
      .prop("key", qualityGateDto.getId())
      .prop("name", qualityGateDto.getName())
      .prop("isDefault", qualityGateData.get().isDefault())
      .endObject();
  }

  private void writeExtensions(JsonWriter json, ComponentDto component, List<ViewProxy<Page>> pages) {
    json.name("extensions").beginArray();
    for (ViewProxy<Page> page : pages) {
      if (page.isUserAuthorized(component)) {
        writePage(json, getPageUrl(page, component), i18n.message(ENGLISH, page.getId() + ".page", page.getTitle()));
      }
    }
    json.endArray();
  }

  private static String getPageUrl(ViewProxy<Page> page, ComponentDto component) {
    String result;
    String componentKey = encodeComponentKey(component);
    if (page.isController()) {
      result = String.format("%s?id=%s", page.getId(), componentKey);
    } else {
      result = String.format("/plugins/resource/%s?page=%s", componentKey, page.getId());
    }
    return result;
  }

  private static String encodeComponentKey(ComponentDto component) {
    String componentKey = component.getKey();
    try {
      componentKey = URLEncoder.encode(componentKey, StandardCharsets.UTF_8.name());
    } catch (UnsupportedEncodingException unknownEncoding) {
      throw new IllegalStateException(unknownEncoding);
    }
    return componentKey;
  }

  private void writeConfiguration(JsonWriter json, ComponentDto component) {
    boolean isAdmin = userSession.hasComponentUuidPermission(ADMIN, component.projectUuid());

    json.name("configuration").beginObject();
    writeConfigPageAccess(json, isAdmin, component);

    if (isAdmin) {
      json.name("extensions").beginArray();
      List<ViewProxy<Page>> configPages = views.getPages(NavigationSection.RESOURCE_CONFIGURATION, component.scope(), component.qualifier(), component.language());
      for (ViewProxy<Page> page : configPages) {
        writePage(json, getPageUrl(page, component), i18n.message(ENGLISH, page.getId() + ".page", page.getTitle()));
      }
      json.endArray();
    }
    json.endObject();
  }

  private void writeConfigPageAccess(JsonWriter json, boolean isAdmin, ComponentDto component) {
    boolean isProject = Qualifiers.PROJECT.equals(component.qualifier());
    boolean showManualMeasures = isAdmin && !Qualifiers.DIRECTORY.equals(component.qualifier());

    json.prop("showSettings", isAdmin && componentTypeHasProperty(component, PROPERTY_CONFIGURABLE));
    json.prop("showQualityProfiles", isProject);
    json.prop("showQualityGates", isProject);
    json.prop("showManualMeasures", showManualMeasures);
    json.prop("showLinks", isAdmin && isProject);
    json.prop("showPermissions", isAdmin && componentTypeHasProperty(component, PROPERTY_HAS_ROLE_POLICY));
    json.prop("showHistory", isAdmin && componentTypeHasProperty(component, PROPERTY_MODIFIABLE_HISTORY));
    json.prop("showUpdateKey", isAdmin && componentTypeHasProperty(component, PROPERTY_UPDATABLE_KEY));
    json.prop("showBackgroundTasks", ActivityAction.isAllowedOnComponentUuid(userSession, component.uuid()));
  }

  private boolean componentTypeHasProperty(ComponentDto component, String resourceTypeProperty) {
    ResourceType resourceType = resourceTypes.get(component.qualifier());
    return resourceType != null && resourceType.getBooleanProperty(resourceTypeProperty);
  }

  private static void writePage(JsonWriter json, String url, String name) {
    json.beginObject()
      .prop("url", url)
      .prop("name", name)
      .endObject();
  }

  private void writeBreadCrumbs(JsonWriter json, DbSession session, ComponentDto component) {
    json.name("breadcrumbs").beginArray();

    List<ComponentDto> breadcrumb = Lists.newArrayList();
    breadcrumb.addAll(dbClient.componentDao().selectAncestors(session, component));
    breadcrumb.add(component);

    for (ComponentDto c : breadcrumb) {
      json.beginObject()
        .prop("key", c.key())
        .prop("name", c.name())
        .prop("qualifier", c.qualifier())
        .endObject();
    }

    json.endArray();
  }
}
