package jigit.resource;

import com.atlassian.jira.security.JiraAuthenticationContext;
import com.atlassian.jira.security.PermissionManager;
import com.atlassian.jira.security.Permissions;
import com.atlassian.jira.user.ApplicationUser;
import com.atlassian.jira.util.I18nHelper;
import jigit.indexer.DisabledRepos;
import jigit.indexer.RepoDataCleaner;
import jigit.indexer.repository.RepoInfo;
import jigit.indexer.repository.RepoInfoFactory;
import jigit.indexer.repository.RepoType;
import jigit.settings.JigitRepo;
import jigit.settings.JigitSettingsManager;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.*;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

@Path("/")
public final class JigitAdminResource {
    @NotNull
    private static final Logger log = LoggerFactory.getLogger(JigitAdminResource.class);
    @NotNull
    private static final TimeUnit TIME_UNIT = TimeUnit.SECONDS;
    @NotNull
    private final JiraAuthenticationContext authCtx;
    @NotNull
    private final PermissionManager permissionManager;
    @NotNull
    private final I18nHelper i18n;
    @NotNull
    private final JigitSettingsManager settingsManager;
    @SuppressWarnings({"NotInitAndNotUsedInspection", "NullableProblems"})
    @NotNull
    @Context
    private HttpServletRequest request;
    @NotNull
    private final RepoInfoFactory repoInfoFactory;
    @NotNull
    private final RepoDataCleaner repoDataCleaner;

    public JigitAdminResource(@NotNull JiraAuthenticationContext authCtx,
                              @NotNull PermissionManager permissionManager,
                              @NotNull JigitSettingsManager settingsManager,
                              @NotNull RepoInfoFactory repoInfoFactory,
                              @NotNull RepoDataCleaner repoDataCleaner) {
        this.authCtx = authCtx;
        this.permissionManager = permissionManager;
        this.i18n = authCtx.getI18nHelper();
        this.settingsManager = settingsManager;
        this.repoInfoFactory = repoInfoFactory;
        this.repoDataCleaner = repoDataCleaner;
    }

    @NotNull
    @POST
    @Path("repo/add")
    @Produces(MediaType.TEXT_HTML)
    public Response addRepo(@NotNull @FormParam("repo_name") @DefaultValue("") String repoName,
                            @NotNull @FormParam("url") @DefaultValue("") String url,
                            @NotNull @FormParam("token") @DefaultValue("") String token,
                            @Nullable @FormParam("repo_type") RepoType repoType,
                            @NotNull @FormParam("repository_id") @DefaultValue("") String repositoryId,
                            @NotNull @FormParam("def_branch") @DefaultValue("") String branch,
                            @NotNull @FormParam("request_timeout") @DefaultValue("10") Integer requestTimeout,
                            @NotNull @FormParam("sleep_timeout") @DefaultValue("10") Integer sleepTimeout,
                            @NotNull @FormParam("sleep_requests") @DefaultValue("100") Integer sleepRequests,
                            @NotNull @FormParam("index_all_branches") @DefaultValue("false") Boolean indexAllBranches) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoType == null || repoName.isEmpty() || url.isEmpty() || token.isEmpty() || repositoryId.isEmpty() || branch.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }
        final JigitRepo existedJigitRepo = settingsManager.getJigitRepo(repoName);
        if (existedJigitRepo != null) {
            return Response.ok(i18n.getText("jigit.error.params.repo.exists", repoName)).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo jigitRepo = new JigitRepo(repoName.trim(), url.trim(), token,
                repoType, repositoryId.trim(),
                branch.trim(), true, (int) TIME_UNIT.toMillis(requestTimeout),
                (int) TIME_UNIT.toMillis(sleepTimeout), sleepRequests, indexAllBranches);

        settingsManager.putJigitRepo(jigitRepo);
        log.info("Jigit setting were updated. Item '" + repoName + "' of type " + repoTypeName(jigitRepo) + " was added.");
        return Response.ok().build();
    }

    @NotNull
    @POST
    @Path("repo/test")
    @Produces(MediaType.TEXT_HTML)
    public Response testRepo(@NotNull @FormParam("repo_name") @DefaultValue("") String repoName,
                             @NotNull @FormParam("url") @DefaultValue("") String url,
                             @NotNull @FormParam("token") @DefaultValue("") String token,
                             @NotNull @FormParam("change_token") @DefaultValue("") HtmlCheckbox changeToken,
                             @Nullable @FormParam("repo_type") RepoType repoType,
                             @NotNull @FormParam("repository_id") @DefaultValue("") String repositoryId,
                             @NotNull @FormParam("def_branch") @DefaultValue("") String branch,
                             @NotNull @FormParam("request_timeout") @DefaultValue("10") Integer requestTimeout) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoType == null || repoName.isEmpty() || url.isEmpty() || repositoryId.isEmpty() || branch.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo existedJigitRepo = settingsManager.getJigitRepo(repoName);
        final JigitRepo jigitRepo;
        if (changeToken.isChecked() || existedJigitRepo == null) {
            jigitRepo = new JigitRepo(repoName, url.trim(), token, repoType, repositoryId.trim(),
                    branch.trim(), true, (int) TIME_UNIT.toMillis(requestTimeout),
                    (int) TIME_UNIT.toMillis(0), 0, false);
        } else {
            jigitRepo = new JigitRepo(repoName, url.trim(), existedJigitRepo.getToken(),
                    existedJigitRepo.getRepoType(), repositoryId.trim(),
                    branch.trim(), true, (int) TIME_UNIT.toMillis(requestTimeout),
                    (int) TIME_UNIT.toMillis(0), 0, false);
        }

        try {
            final Collection<RepoInfo> repoInfos = repoInfoFactory.build(jigitRepo);
            if (repoInfos.isEmpty()) {
                return Response.ok("No repositories found for path '" + repositoryId + "'").status(Response.Status.BAD_REQUEST).build();
            }
            final RepoInfo repoInfo = repoInfos.iterator().next();
            repoInfo.getApiAdapter().getHeadCommitSha1(repoInfo.getDefaultBranch());
        } catch (Exception e) {
            log.error("An exception was occurred while testing a repository " + repoName, e);
            return Response.ok(e.getMessage() + "\n. See JIRA log for details.").status(Response.Status.BAD_REQUEST).build();
        }

        return Response.ok().build();
    }

    @NotNull
    @POST
    @Path("repo/edit")
    @Produces(MediaType.TEXT_HTML)
    public Response editRepo(@NotNull @FormParam("repo_name") @DefaultValue("") String repoName,
                             @NotNull @FormParam("url") @DefaultValue("") String url,
                             @NotNull @FormParam("token") @DefaultValue("") String token,
                             @NotNull @FormParam("repository_id") @DefaultValue("") String repositoryId,
                             @NotNull @FormParam("def_branch") @DefaultValue("") String branch,
                             @NotNull @FormParam("change_token") @DefaultValue("") HtmlCheckbox changeToken,
                             @NotNull @FormParam("request_timeout") @DefaultValue("10") Integer requestTimeout,
                             @NotNull @FormParam("sleep_timeout") @DefaultValue("10") Integer sleepTimeout,
                             @NotNull @FormParam("sleep_requests") @DefaultValue("100") Integer sleepRequest,
                             @NotNull @FormParam("index_all_branches") @DefaultValue("false") Boolean indexAllBranches) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoName.isEmpty() || url.isEmpty() || repositoryId.isEmpty() || branch.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo jigitRepo = settingsManager.getJigitRepo(repoName);
        if (jigitRepo == null) {
            return Response.ok(i18n.getText("jigit.error.params.invalid")).status(Response.Status.BAD_REQUEST).build();
        }

        final String newToken = changeToken.isChecked() ? token : jigitRepo.getToken();
        final JigitRepo newRepo = new JigitRepo(repoName.trim(), url.trim(), newToken,
                jigitRepo.getRepoType(), repositoryId.trim(),
                branch.trim(), jigitRepo.isEnabled(), (int) TIME_UNIT.toMillis(requestTimeout),
                (int) TIME_UNIT.toMillis(sleepTimeout), sleepRequest, indexAllBranches);
        newRepo.addBranches(jigitRepo.getBranches());

        settingsManager.putJigitRepo(newRepo);
        log.info("Jigit setting were updated. Item '" + repoName + "' of type " + repoTypeName(jigitRepo) + " was edited.");
        return Response.ok().build();
    }

    @NotNull
    @POST
    @Path("/repo/{repo:.+}/remove")
    @Produces(MediaType.TEXT_HTML)
    public Response removeRepo(@NotNull @PathParam("repo") @DefaultValue("") String repoName) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoName.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }

        settingsManager.removeJigitRepo(repoName);
        log.info("Jigit setting were updated. Item '" + repoName + "' was removed.");
        return getReferrerResponse(request);
    }

    @NotNull
    @POST
    @Path("/repo/{repo:.+}/clear")
    @Produces(MediaType.TEXT_HTML)
    public Response clearRepo(@NotNull @PathParam("repo") @DefaultValue("") String repoName) throws InterruptedException, IOException {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoName.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }
        final JigitRepo jigitRepo = settingsManager.getJigitRepo(repoName);
        if (jigitRepo == null) {
            return Response.noContent().status(Response.Status.NOT_FOUND).build();
        }
        repoDataCleaner.clearRepoData(repoInfoFactory.build(jigitRepo));
        log.info("Jigit setting were updated. Data of item '" + repoName + "' of type " + repoTypeName(jigitRepo) + " was deleted.");
        return getReferrerResponse(request);
    }

    @NotNull
    @POST
    @Path("/repo/{repo:.+}/activity")
    @Produces(MediaType.TEXT_HTML)
    public Response disableRepo(@NotNull @PathParam("repo") @DefaultValue("") String repoName,
                                @Nullable @FormParam("enabled") Boolean enabled) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (enabled == null || repoName.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }
        final JigitRepo jigitRepo = settingsManager.getJigitRepo(repoName);
        if (jigitRepo == null) {
            return Response.ok(i18n.getText("jigit.error.params.invalid")).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo newRepo = new JigitRepo(jigitRepo.getRepoName(), jigitRepo.getServerUrl(),
                jigitRepo.getToken(), jigitRepo.getRepoType(), jigitRepo.getRepositoryId(),
                jigitRepo.getDefaultBranch(), enabled, jigitRepo.getRequestTimeout(),
                jigitRepo.getSleepTimeout(), jigitRepo.getSleepRequests(), jigitRepo.isIndexAllBranches());
        newRepo.addBranches(jigitRepo.getBranches());

        if (enabled) {
            DisabledRepos.instance.markEnabled(repoName);
        } else {
            DisabledRepos.instance.markDisabled(repoName);
        }
        settingsManager.putJigitRepo(newRepo);
        log.info("Jigit setting were updated. Item '" + repoName + "' of type " + repoTypeName(jigitRepo) + " was " + (enabled ? "enabled" : "disabled") + '.');
        return getReferrerResponse(request);
    }

    @NotNull
    @POST
    @Path("/repo/{repo:.+}/branch/add")
    @Produces(MediaType.TEXT_HTML)
    public Response addBranch(@NotNull @PathParam("repo") @DefaultValue("") String repoName,
                              @NotNull @FormParam("branch") @DefaultValue("") String branch) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoName.isEmpty() || branch.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo jigitRepo = settingsManager.getJigitRepo(repoName);
        if (jigitRepo == null) {
            return Response.ok(i18n.getText("jigit.error.params.invalid")).status(Response.Status.BAD_REQUEST).build();
        }
        jigitRepo.addBranch(branch.trim());
        settingsManager.putJigitRepo(jigitRepo);
        log.info("Jigit setting were updated. Branch '" + branch + "' was added to repo '" + repoName + "'.");
        return Response.ok().build();
    }

    @NotNull
    @POST
    @Path("/repo/{repo:.+}/branch/{branch:.+}/remove")
    @Produces(MediaType.TEXT_HTML)
    public Response removeBranch(@NotNull @PathParam("repo") @DefaultValue("") String repoName,
                                 @NotNull @PathParam("branch") @DefaultValue("") String branch) {
        final Response response = checkUserPermissions(authCtx.getLoggedInUser(), Permissions.ADMINISTER);
        if (response != null) {
            return response;
        }
        if (repoName.isEmpty() || branch.isEmpty()) {
            return Response.ok(i18n.getText("jigit.error.params.empty")).status(Response.Status.BAD_REQUEST).build();
        }

        final JigitRepo jigitRepo = settingsManager.getJigitRepo(repoName);
        if (jigitRepo == null) {
            return Response.ok(i18n.getText("jigit.error.params.invalid")).status(Response.Status.BAD_REQUEST).build();
        }
        jigitRepo.removeBranch(branch);
        settingsManager.putJigitRepo(jigitRepo);
        log.info("Jigit setting were updated. Branch '" + branch + "' was removed from repo '" + repoName + "'.");
        return getReferrerResponse(request);
    }

    @NotNull
    private String repoTypeName(@NotNull JigitRepo jigitRepo) {
        return i18n.getText(jigitRepo.getRepoType().getDisplayName());
    }

    @Nullable
    private Response checkUserPermissions(@Nullable ApplicationUser user, int permission) {
        Response resp = null;
        final String errorMessage;

        if (user == null) {
            errorMessage = "User is not logged in";
            resp = Response.ok(errorMessage).status(Response.Status.UNAUTHORIZED).build();
        } else if (!permissionManager.hasPermission(permission, user)) {
            errorMessage = "Invalid permissions for " + user.getName();
            resp = Response.ok(errorMessage).status(Response.Status.FORBIDDEN).build();
        }

        return resp;
    }

    @NotNull
    private static Response getReferrerResponse(@NotNull HttpServletRequest request) {
        final String referrer = request.getHeader("referer");
        final URI uri;
        try {
            uri = new URI(referrer);
        } catch (URISyntaxException e) {
            return Response.ok(e.getMessage()).status(Response.Status.INTERNAL_SERVER_ERROR).build();
        }

        return Response.seeOther(uri).build();
    }
}
