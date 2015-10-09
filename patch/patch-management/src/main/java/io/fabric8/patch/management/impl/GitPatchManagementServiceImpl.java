/**
 *  Copyright 2005-2015 Red Hat, Inc.
 *
 *  Red Hat licenses this file to you under the Apache License, version
 *  2.0 (the "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 *  implied.  See the License for the specific language governing
 *  permissions and limitations under the License.
 */
package io.fabric8.patch.management.impl;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.fabric8.patch.management.Artifact;
import io.fabric8.patch.management.BundleUpdate;
import io.fabric8.patch.management.ManagedPatch;
import io.fabric8.patch.management.Patch;
import io.fabric8.patch.management.PatchData;
import io.fabric8.patch.management.PatchDetailsRequest;
import io.fabric8.patch.management.PatchException;
import io.fabric8.patch.management.PatchKind;
import io.fabric8.patch.management.PatchManagement;
import io.fabric8.patch.management.PatchResult;
import io.fabric8.patch.management.Utils;
import io.fabric8.patch.management.io.EOLFixingFileUtils;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;
import org.eclipse.jgit.api.CherryPickResult;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.MergeCommand;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.RevertCommand;
import org.eclipse.jgit.api.Status;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheEntry;
import org.eclipse.jgit.lib.IndexDiff;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectLoader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevObject;
import org.eclipse.jgit.revwalk.RevTag;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.transport.RefSpec;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.Version;
import org.osgi.framework.VersionRange;
import org.osgi.framework.startlevel.BundleStartLevel;

import static io.fabric8.patch.management.Artifact.isSameButVersion;
import static io.fabric8.patch.management.Utils.*;

/**
 * <p>An implementation of Git-based patch management system. Deals with patch distributions and their unpacked
 * content.</p>
 * <p>This class delegates lower-level operations to {@link GitPatchRepository} and performs more complex git
 * operations in temporary clone+working copies.</p>
 */
public class GitPatchManagementServiceImpl implements PatchManagement, GitPatchManagementService {

    private static final String[] MANAGED_DIRECTORIES = new String[] {
            "bin", "etc", "lib", "fabric", "licenses", "metatype"
    };

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("patch-management-(\\d+\\.\\d+\\.\\d+(?:\\.[^\\.]+)?)\\.jar");

    /* A pattern of commit message when adding baseling distro */
    private static final String MARKER_BASELINE_COMMIT_PATTERN = "[PATCH/baseline] Installing baseline-%s";
    private static final String MARKER_BASELINE_RESET_OVERRIDES_PATTERN = "[PATCH/baseline] baseline-%s - resetting etc/overrides.properties";

    /* Patterns for rolling patch installation */
    private static final String MARKER_R_PATCH_INSTALLATION_PATTERN = "[PATCH] Installing rollup patch %s";
    private static final String MARKER_R_PATCH_RESET_OVERRIDES_PATTERN = "[PATCH] Rollup patch %s - resetting etc/overrides.properties";

    /* Patterns for non-rolling patch installation */
    private static final String MARKER_P_PATCH_INSTALLATION_PATTERN = "[PATCH] Installing patch %s";

    /** A pattern of commit message when installing patch-management (this) bundle in etc/startup.properties */
    private static final String MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN =
            "[PATCH/management] patch-management-%s.jar installed in etc/startup.properties";

    /** Commit message when applying user changes to managed directories */
    private static final String MARKER_USER_CHANGES_COMMIT = "[PATCH] Apply user changes";

    private final BundleContext bundleContext;
    private final BundleContext systemContext;

    private GitPatchRepository gitPatchRepository;

    // ${karaf.home}
    private File karafHome;
    // main patches directory at ${fuse.patch.location} (defaults to ${karaf.home}/patches)
    private File patchesDir;

    // latched when git repository is initialized
    private CountDownLatch initialized = new CountDownLatch(1);

    /* patch installation support */

    protected Map<String, Git> pendingTransactions = new HashMap<>();
    protected Map<String, PatchKind> pendingTransactionsTypes = new HashMap<>();

    public GitPatchManagementServiceImpl(BundleContext context) {
        this.bundleContext = context;
        this.systemContext = context.getBundle(0).getBundleContext();
        karafHome = new File(systemContext.getProperty("karaf.home"));
        String patchLocation = systemContext.getProperty("fuse.patch.location");
        if (patchLocation != null) {
            patchesDir = new File(patchLocation);
        }
        GitPatchRepositoryImpl repository = new GitPatchRepositoryImpl(karafHome, patchesDir);
        setGitPatchRepository(repository);
    }

    public GitPatchRepository getGitPatchRepository() {
        return gitPatchRepository;
    }

    public void setGitPatchRepository(GitPatchRepository repository) {
        this.gitPatchRepository = repository;
    }

    @Override
    public List<Patch> listPatches(boolean details) throws PatchException {
        List<Patch> patches = new LinkedList<>();
        File[] patchDescriptors = patchesDir.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return name.endsWith(".patch") && new File(dir, name).isFile();
            }
        });

        try {
            for (File pd : patchDescriptors) {
                Patch p = loadPatch(pd, details);
                patches.add(p);
            }
        } catch (IOException e) {
            throw new PatchException(e.getMessage(), e);
        }

        return patches;
    }

    /**
     * Retrieves patch information from existing file
     * @param patchDescriptor existing file with patch descriptor (<code>*.patch</code> file)
     * @param details whether the returned {@link Patch} should contain {@link ManagedPatch} information
     * @return
     * @throws IOException
     */
    private Patch loadPatch(File patchDescriptor, boolean details) throws IOException {
        Patch p = new Patch();

        if (!patchDescriptor.exists() || !patchDescriptor.isFile()) {
            return null;
        }

        PatchData data = PatchData.load(new FileInputStream(patchDescriptor));
        p.setPatchData(data);

        File patchDirectory = new File(patchesDir, FilenameUtils.getBaseName(patchDescriptor.getName()));
        if (patchDirectory.exists() && patchDirectory.isDirectory()) {
            // not every descriptor downloaded may be a ZIP file, not every patch has content
            data.setPatchDirectory(patchDirectory);
        }
        data.setPatchLocation(patchesDir);

        File resultFile = new File(patchesDir, FilenameUtils.getBaseName(patchDescriptor.getName()) + ".patch.result");
        if (resultFile.exists() && resultFile.isFile()) {
            PatchResult result = PatchResult.load(data, new FileInputStream(resultFile));
            p.setResult(result);
        }

        if (details) {
            ManagedPatch mp = gitPatchRepository.getManagedPatch(data.getId());
            p.setManagedPatch(mp);
        }

        return p;
    }

    @Override
    public Patch loadPatch(PatchDetailsRequest request) throws PatchException {
        File descriptor = new File(patchesDir, request.getPatchId() + ".patch");
        try {
            Patch patch = loadPatch(descriptor, true);
            if (patch == null) {
                return null;
            }
            Git repo = gitPatchRepository.findOrCreateMainGitRepository();
            List<DiffEntry> diff = null;
            if (request.isFiles() || request.isDiff()) {
                // fetch the information from git
                ObjectId commitId = repo.getRepository().resolve(patch.getManagedPatch().getCommitId());
                RevCommit commit = new RevWalk(repo.getRepository()).parseCommit(commitId);
                diff = gitPatchRepository.diff(repo, commit.getParent(0), commit);
            }
            if (request.isBundles()) {
                // it's already in PatchData
            }
            if (request.isFiles()) {
                for (DiffEntry de : diff) {
                    DiffEntry.ChangeType ct = de.getChangeType();
                    String newPath = de.getNewPath();
                    String oldPath = de.getOldPath();
                    switch (ct) {
                        case ADD:
                            patch.getManagedPatch().getFilesAdded().add(newPath);
                            break;
                        case MODIFY:
                            patch.getManagedPatch().getFilesModified().add(newPath);
                            break;
                        case DELETE:
                            patch.getManagedPatch().getFilesRemoved().add(oldPath);
                            break;
                    }
                }
            }
            if (request.isDiff()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                DiffFormatter formatter = new DiffFormatter(baos);
                formatter.setContext(4);
                formatter.setRepository(repo.getRepository());
                for (DiffEntry de : diff) {
                    formatter.format(de);
                }
                formatter.flush();
                patch.getManagedPatch().setUnifiedDiff(new String(baos.toByteArray(), "UTF-8"));
            }
            return patch;
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        }
    }

    @Override
    public List<PatchData> fetchPatches(URL url) {
        try {
            List<PatchData> patches = new ArrayList<>(1);

            File patchFile = new File(patchesDir, Long.toString(System.currentTimeMillis()) + ".patch.tmp");
            InputStream input = url.openStream();
            FileOutputStream output = new FileOutputStream(patchFile);
            ZipFile zf = null;
            try {
                IOUtils.copy(input, output);
            } finally {
                IOUtils.closeQuietly(input);
                IOUtils.closeQuietly(output);
            }
            try {
                zf = new ZipFile(patchFile);
            } catch (IOException ignored) {
            }

            // patchFile may "be" a patch descriptor or be a ZIP file containing descriptor
            PatchData patchData = null;
            // in case patch ZIP file has no descriptor, we'll "generate" patch data on the fly
            // no descriptor -> assume we have rollup patch or even full, new distribution
            PatchData fallbackPatchData = new PatchData(FilenameUtils.getBaseName(url.getPath()));
            fallbackPatchData.setGenerated(true);
            fallbackPatchData.setRollupPatch(true);
            fallbackPatchData.setPatchDirectory(new File(patchesDir, fallbackPatchData.getId()));

            if (zf != null) {
                File systemRepo = getSystemRepository(karafHome, systemContext);
                try {
                    List<ZipArchiveEntry> otherResources = new LinkedList<>();
                    boolean skipRootDir = false;
                    for (Enumeration<ZipArchiveEntry> e = zf.getEntries(); e.hasMoreElements(); ) {
                        ZipArchiveEntry entry = e.nextElement();
                        if (!skipRootDir && (entry.getName().startsWith("jboss-fuse-")
                                || entry.getName().startsWith("jboss-a-mq-"))) {
                            skipRootDir = true;
                        }
                        if (entry.isDirectory() || entry.isUnixSymlink()) {
                            continue;
                        }
                        String name = entry.getName();
                        if (skipRootDir) {
                            name = name.substring(name.indexOf('/') + 1);
                        }
                        if (!name.contains("/") && name.endsWith(".patch")) {
                            // patch descriptor in ZIP's root directory
                            // TODO why there must be only one?
                            if (patchData == null) {
                                // load data from patch descriptor inside ZIP. This may or may not be a rollup
                                // patch
                                File target = new File(patchesDir, name);
                                extractZipEntry(zf, entry, target);
                                patchData = loadPatchData(target);
                                patchData.setGenerated(false);
                                File targetDirForPatchResources = new File(patchesDir, patchData.getId());
                                patchData.setPatchDirectory(targetDirForPatchResources);
                                target.renameTo(new File(patchesDir, patchData.getId() + ".patch"));
                                patches.add(patchData);
                            } else {
                                throw new PatchException(
                                        String.format("Multiple patch descriptors: already have patch %s and now encountered entry %s",
                                                patchData.getId(), name));
                            }
                        } else {
                            File target = null;
                            if (name.startsWith("system/")) {
                                // copy to ${karaf.default.repository}
                                target = new File(systemRepo, name.substring("system/".length()));
                            } else if (name.startsWith("repository/")) {
                                // copy to ${karaf.default.repository}
                                target = new File(systemRepo, name.substring("repository/".length()));
                            } else {
                                // other files that should be applied to ${karaf.home} when the patch is installed
                                otherResources.add(entry);
                            }
                            if (target != null) {
                                extractAndTrackZipEntry(fallbackPatchData, zf, entry, target, skipRootDir);
                            }
                        }
                    }

                    File targetDirForPatchResources = new File(patchesDir, patchData == null ? fallbackPatchData.getId() : patchData.getId());
                    // now copy non-maven resources (we should now know where to copy them)
                    for (ZipArchiveEntry entry : otherResources) {
                        String name = entry.getName();
                        if (skipRootDir) {
                            name = name.substring(name.indexOf('/'));
                        }
                        File target = new File(targetDirForPatchResources, name);
                        extractAndTrackZipEntry(fallbackPatchData, zf, entry, target, skipRootDir);
                    }
                } finally {
                    if (zf != null) {
                        zf.close();
                    }
                    if (patchFile != null) {
                        patchFile.delete();
                    }
                }
            } else {
                // If the file is not a zip/jar, assume it's a single patch file
                patchData = loadPatchData(patchFile);
                // no patch directory - no attached content, assuming only references to bundles
                patchData.setPatchDirectory(null);
                patchFile.renameTo(new File(patchesDir, patchData.getId() + ".patch"));
                patches.add(patchData);
            }

            if (patches.size() == 0) {
                // let's use generated patch descriptor
                File generatedPatchDescriptor = new File(patchesDir, fallbackPatchData.getId() + ".patch");
                FileOutputStream out = new FileOutputStream(generatedPatchDescriptor);
                try {
                    fallbackPatchData.storeTo(out);
                } finally {
                    IOUtils.closeQuietly(out);
                }
                patches.add(fallbackPatchData);
            }

            return patches;
        } catch (IOException e) {
            throw new PatchException("Unable to download patch from url " + url, e);
        }
    }

    /**
     * Reads content of patch descriptor into non-(yet)-managed patch data structure
     * @param patchDescriptor
     * @return
     */
    private PatchData loadPatchData(File patchDescriptor) throws IOException {
        Properties properties = new Properties();
        FileInputStream inputStream = null;
        try {
            inputStream = new FileInputStream(patchDescriptor);
            properties.load(inputStream);
            return PatchData.load(properties);
        } finally {
            IOUtils.closeQuietly(inputStream);
        }
    }

    /**
     * <p>This method turns static information about a patch into managed patch - i.e., patch added to git
     * repository.</p>
     *
     * <p>Such patch has its own branch ready to be merged (when patch is installed). Before installation we can verify
     * the patch,
     * examine the content, check the differences, conflicts and perform simulation (merge to temporary branch created
     * from <code>master</code>)</p>
     *
     * <p>The strategy is as follows:<ul>
     *     <li><code>master</code> branch in git repository tracks all changes (from baselines, patch-management
     *     system, patches and user changes)</li>
     *     <li>Initially there are 3 commits: baseline, patch-management bundle installation in etc/startup.properties,
     *     initial user changes</li>
     *     <li>We always <strong>tag the baseline commit</strong></li>
     *     <li>User changes may be applied each time Framework is restarted</li>
     *     <li>When we add a patch, we create <em>named branch</em> from the <strong>latest baseline</strong></li>
     *     <li>When we install a patch, we <strong>merge</strong> the patch branch with the master (that may contain
     *     additional user changes)</li>
     *     <li>When patch ZIP contains new baseline distribution, after merging patch branch, we tag the merge commit
     *     in <code>master</code> branch as new baseline</li>
     *     <li>Branches for new patches will then be created from new baseline commit</li>
     * </ul></p>
     * @param patchData
     * @return
     */
    @Override
    public Patch trackPatch(PatchData patchData) throws PatchException {
        try {
            awaitInitialization();
        } catch (InterruptedException e) {
            throw new PatchException("Patch management system is not ready yet");
        }
        Git fork = null;
        try {
            Git mainRepository = gitPatchRepository.findOrCreateMainGitRepository();
            // prepare single fork for all the below operations
            fork = gitPatchRepository.cloneRepository(mainRepository, true);

            // 1. find current baseline
            RevTag latestBaseline = gitPatchRepository.findCurrentBaseline(fork);
            if (latestBaseline == null) {
                throw new PatchException("Can't find baseline distribution tracked in patch management. Is patch management initialized?");
            }

            // prepare managed patch instance - that contains information after adding patch to patch-branch
            ManagedPatch mp = new ManagedPatch();

            // the commit from the patch should be available from "master" branch
            RevCommit commit = new RevWalk(fork.getRepository()).parseCommit(latestBaseline.getObject());

            // create dedicated branch for this patch. We'll immediately add patch content there so we can examine the
            // changes from the latest baseline
            fork.checkout()
                    .setCreateBranch(true)
                    .setName(patchData.getId())
                    .setStartPoint(commit)
                    .call();

            // copy patch resources (but not maven artifacts from system/ or repository/) to working copy
            if (patchData.getPatchDirectory() != null) {
                copyManagedDirectories(patchData.getPatchDirectory(), fork.getRepository().getWorkTree(), false);
            }

            // add the changes
            fork.add().addFilepattern(".").call();

            // commit the changes (patch vs. baseline) to patch branch
            gitPatchRepository.prepareCommit(fork, String.format("[PATCH] Tracking patch %s", patchData.getId())).call();

            // push the patch branch
            gitPatchRepository.push(fork, patchData.getId());

            return new Patch(patchData, gitPatchRepository.getManagedPatch(patchData.getId()));
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * This service is published before it can initialize the system. It may be an issue in integration tests.
     */
    private void awaitInitialization() throws InterruptedException {
        initialized.await(30, TimeUnit.SECONDS);
    }

    @Override
    public String beginInstallation(PatchKind kind) {
        String tx = null;
        try {
            Git fork = gitPatchRepository.cloneRepository(gitPatchRepository.findOrCreateMainGitRepository(), true);
            Ref installationBranch = null;

            // let's pick up latest user changes
            applyUserChanges(fork);

            switch (kind) {
                case ROLLUP:
                    // create temporary branch from the current baseline - rollup patch installation is a rebase
                    // of existing user changes on top of new baseline
                    RevTag currentBaseline = gitPatchRepository.findCurrentBaseline(fork);
                    installationBranch = fork.checkout()
                            .setName(String.format("patch-install-%s", GitPatchRepository.TS.format(new Date())))
                            .setCreateBranch(true)
                            .setStartPoint(currentBaseline.getTagName() + "^{commit}")
                            .call();
                    break;
                case NON_ROLLUP:
                    // create temporary branch from master/HEAD - non-rollup patch installation is cherry-pick
                    // of non-rollup patch commit over existing user changes - we can fast forward when finished
                    installationBranch = fork.checkout()
                            .setName(String.format("patch-install-%s", GitPatchRepository.TS.format(new Date())))
                            .setCreateBranch(true)
                            .setStartPoint("master")
                            .call();
                    break;
            }

            pendingTransactionsTypes.put(installationBranch.getName(), kind);
            pendingTransactions.put(installationBranch.getName(), fork);

            return installationBranch.getName();
        } catch (IOException | GitAPIException e) {
            if (tx != null) {
                pendingTransactions.remove(tx);
                pendingTransactionsTypes.remove(tx);
            }
            throw new PatchException(e.getMessage(), e);
        }
    }

    @Override
    public void install(String transaction, Patch patch, List<BundleUpdate> bundleUpdatesInThisPatch) {
        transactionIsValid(transaction, patch);

        Git fork = pendingTransactions.get(transaction);
        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP: {
                    System.out.printf("Installing rollup patch \"%s\"%n", patch.getPatchData().getId());
                    System.out.flush();
                    // we can install only one rollup patch within single transaction
                    // and it is equal to cherry-picking all user changes on top of transaction branch
                    // after cherry-picking the commit from the rollup patch branch
                    // rollup patches do their own update to startup.properties
                    // we're operating on patch branch, HEAD of the patch branch points to the baseline
                    ObjectId since = fork.getRepository().resolve("HEAD^{commit}");
                    // we'll pick all user changes between baseline and master without P installations
                    ObjectId to = fork.getRepository().resolve("master^{commit}");
                    Iterable<RevCommit> masterChanges = fork.log().addRange(since, to).call();
                    List<RevCommit> userChanges = new LinkedList<>();
                    for (RevCommit rc : masterChanges) {
                        if (isUserChangeCommit(rc)) {
                            userChanges.add(rc);
                        }
                    }

                    // pick the rollup patch
                    fork.cherryPick()
                            .include(fork.getRepository().resolve(patch.getManagedPatch().getCommitId()))
                            .setNoCommit(true)
                            .call();

                    gitPatchRepository.prepareCommit(fork,
                            String.format(MARKER_R_PATCH_INSTALLATION_PATTERN, patch.getPatchData().getId())).call();

                    // next commit - reset overrides.properties - this is 2nd step of installing rollup patch
                    // we are doing it even if the commit is going to be empty - this is the same step as after
                    // creating initial baseline
                    resetOverrides(fork.getRepository().getWorkTree());
                    fork.add().addFilepattern("etc/overrides.properties").call();
                    RevCommit c = gitPatchRepository.prepareCommit(fork,
                            String.format(MARKER_R_PATCH_RESET_OVERRIDES_PATTERN, patch.getPatchData().getId())).call();

                    // tag the new rollup patch as new baseline
                    String newFuseVersion = determineVersion(fork.getRepository().getWorkTree(), "fuse");
                    fork.tag()
                            .setName(String.format("baseline-%s", newFuseVersion))
                            .setObjectId(c)
                            .call();

                    // reapply those user changes that are not conflicting
                    // for each conflicting cherry-pick we do a backup of user files, to be able to restore them
                    // when rollup patch is rolled back
                    ListIterator<RevCommit> it = userChanges.listIterator(userChanges.size());
                    int prefixSize = Integer.toString(userChanges.size()).length();
                    int count = 1;

                    while (it.hasPrevious()) {
                        RevCommit userChange = it.previous();
                        String prefix = String.format("%0" + prefixSize + "d-%s", count++, userChange.getName());
                        CherryPickResult result = fork.cherryPick()
                                .include(userChange)
                                .setNoCommit(true)
                                .call();
                        handleCherryPickConflict(patch.getPatchData().getPatchDirectory(), fork, result, userChange,
                                false, PatchKind.ROLLUP, prefix, true);

                        // always commit even empty changes - to be able to restore user changes when rolling back
                        // rollup patch.
                        // commit has the original commit id appended to the message.
                        // when we rebase on OLDER baseline (rollback) we restore backed up files based on this
                        // commit id (from patches/patch-id.backup/nr-commit directory)
                        String newMessage = userChange.getFullMessage() + "\n\n";
                        newMessage += prefix;
                        gitPatchRepository.prepareCommit(fork, newMessage).call();

                        // we may have unadded changes - when file mode is changed
                        fork.reset().setMode(ResetCommand.ResetType.HARD).call();
                    }

                    // finally - let's get rid of all the tags related to non-rollup patches installed between
                    // previous baseline and previous HEAD, because installing rollup patch makes all previous P
                    // patches obsolete
                    RevWalk walk = new RevWalk(fork.getRepository());
                    RevCommit c1 = walk.parseCommit(since);
                    RevCommit c2 = walk.parseCommit(to);
                    Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, c1, c2);
                    for (Map.Entry<String, RevTag> entry : tags.entrySet()) {
                        if (entry.getKey().startsWith("patch-")) {
                            fork.tagDelete().setTags(entry.getKey()).call();
                            fork.push()
                                    .setRefSpecs(new RefSpec()
                                            .setSource(null)
                                            .setDestination("refs/tags/" + entry.getKey()))
                                    .call();
                        }
                    }
                    break;
                }
                case NON_ROLLUP: {
                    System.out.printf("Installing non-rollup patch \"%s\"%n", patch.getPatchData().getId());
                    System.out.flush();
                    // simply cherry-pick patch commit to transaction branch
                    // non-rollup patches require manual change to artifact references in all files

                    // pick the non-rollup patch
                    RevCommit commit = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(patch.getManagedPatch().getCommitId()));
                    CherryPickResult result = fork.cherryPick()
                            .include(commit)
                            .setNoCommit(true)
                            .call();
                    handleCherryPickConflict(patch.getPatchData().getPatchDirectory(), fork, result, commit,
                            true, PatchKind.NON_ROLLUP, null, true);

                    // there are several files in ${karaf.home} that need to be changed together with patch
                    // commit, to make them reference updated bundles (paths, locations, ...)
                    updateFileReferences(fork, patch.getPatchData(), bundleUpdatesInThisPatch);
                    updateOverrides(fork.getRepository().getWorkTree(), patch.getPatchData());
                    fork.add().addFilepattern(".").call();

                    // always commit non-rollup patch
                    RevCommit c = gitPatchRepository.prepareCommit(fork,
                        String.format(MARKER_P_PATCH_INSTALLATION_PATTERN, patch.getPatchData().getId())).call();

                    // we may have unadded changes - when file mode is changed
                    fork.reset().setMode(ResetCommand.ResetType.HARD).call();

                    // tag the installed patch (to easily rollback and to prevent another installation)
                    fork.tag()
                            .setName(String.format("patch-%s", patch.getPatchData().getId().replace(' ', '-')))
                            .setObjectId(c)
                            .call();

                    break;
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        }
    }

    /**
     * <p>Updates existing <code>etc/overrides.properties</code> after installing single {@link PatchKind#NON_ROLLUP}
     * patch.</p>
     * @param workTree
     * @param patchData
     */
    private void updateOverrides(File workTree, PatchData patchData) throws IOException {
        List<String> currentOverrides = FileUtils.readLines(new File(workTree, "etc/overrides.properties"));

        for (String bundle : patchData.getBundles()) {
            Artifact artifact = mvnurlToArtifact(bundle, true);
            if (artifact == null) {
                continue;
            }

            // Compute patch bundle version and range
            VersionRange range;
            Version oVer = new Version(artifact.getVersion());
            String vr = patchData.getVersionRange(bundle);
            String override;
            if (vr != null && !vr.isEmpty()) {
                override = bundle + ";range=" + vr;
                range = new VersionRange(vr);
            } else {
                override = bundle;
                Version v1 = new Version(oVer.getMajor(), oVer.getMinor(), 0);
                Version v2 = new Version(oVer.getMajor(), oVer.getMinor() + 1, 0);
                range = new VersionRange(VersionRange.LEFT_CLOSED, v1, v2, VersionRange.RIGHT_OPEN);
            }

            // Process overrides.properties
            boolean matching = false;
            boolean added = false;
            for (int i = 0; i < currentOverrides.size(); i++) {
                String line = currentOverrides.get(i).trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    Artifact overrideArtifact = mvnurlToArtifact(line, true);
                    if (overrideArtifact != null) {
                        Version ver = new Version(overrideArtifact.getVersion());
                        if (isSameButVersion(artifact, overrideArtifact) && range.includes(ver)) {
                            matching = true;
                            if (ver.compareTo(oVer) < 0) {
                                // Replace old override with the new one
                                currentOverrides.set(i, override);
                                added = true;
                            }
                        }
                    }
                }
            }
            // If there was not matching bundles, add it
            if (!matching) {
                currentOverrides.add(override);
            }
        }

        FileUtils.writeLines(new File(workTree, "etc/overrides.properties"), currentOverrides, IOUtils.LINE_SEPARATOR_UNIX);
    }

    /**
     * <p>Creates/truncates <code>etc/overrides.properties</code></p>
     * <p>Each baseline ships new feature repositories and from this point (or the point where new rollup patch
     * is installed) we should start with 0-sized overrides.properties in order to have easier non-rollup
     * patch installation - P-patch should not ADD overrides.properties - they have to only MODIFY it because
     * it's easier to revert such modification (in case P-patch is rolled back - probably in different order
     * than it was installed)</p>
     * @param karafHome
     * @throws IOException
     */
    private void resetOverrides(File karafHome) throws IOException {
        File overrides = new File(karafHome, "etc/overrides.properties");
        if (overrides.isFile()) {
            overrides.delete();
        }
        overrides.createNewFile();
    }

    @Override
    public void commitInstallation(String transaction) {
        transactionIsValid(transaction, null);

        Git fork = pendingTransactions.get(transaction);

        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP: {
                    // hard reset of master branch to point to transaction branch + apply changes to ${karaf.home}
                    fork.checkout()
                            .setName("master")
                            .call();

                    // before we reset master to originate from new baseline, let's find previous baseline
                    RevTag baseline = gitPatchRepository.findCurrentBaseline(fork);
                    RevCommit c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(baseline.getTagName() + "^{commit}"));

                    // hard reset of master branch - to point to other branch, originating from new baseline
                    fork.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(transaction)
                            .call();
                    gitPatchRepository.push(fork);

                    RevCommit c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));

                    // apply changes from single range of commits
                    applyChanges(fork, c1, c2);
                    break;
                }
                case NON_ROLLUP: {
                    // fast forward merge of master branch with transaction branch
                    fork.checkout()
                            .setName("master")
                            .call();
                    // current version of ${karaf.home}
                    RevCommit c1 = new RevWalk(fork.getRepository()).parseCommit(fork.getRepository().resolve("HEAD"));

                    // fast forward over patch-installation branch - possibly over more than 1 commit
                    fork.merge()
                            .setFastForward(MergeCommand.FastForwardMode.FF_ONLY)
                            .include(fork.getRepository().resolve(transaction))
                            .call();

                    gitPatchRepository.push(fork);

                    // apply a change from commits of all installed patches
                    RevCommit c2 = new RevWalk(fork.getRepository()).parseCommit(fork.getRepository().resolve("HEAD"));
                    applyChanges(fork, c1, c2);
                    break;
                }
            }
            gitPatchRepository.push(fork);
        } catch (GitAPIException | IOException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            gitPatchRepository.closeRepository(fork, true);
        }

        pendingTransactions.remove(transaction);
        pendingTransactionsTypes.remove(transaction);
    }

    @Override
    public void rollbackInstallation(String transaction) {
        transactionIsValid(transaction, null);

        Git fork = pendingTransactions.get(transaction);

        try {
            switch (pendingTransactionsTypes.get(transaction)) {
                case ROLLUP:
                case NON_ROLLUP:
                    // simply do nothing - do not push changes to origin
                    break;
            }
        } finally {
            gitPatchRepository.closeRepository(fork, true);
        }

        pendingTransactions.remove(transaction);
        pendingTransactionsTypes.remove(transaction);
    }

    @Override
    public void rollback(PatchData patchData) {
        Git fork = null;
        try {
            fork = gitPatchRepository.cloneRepository(gitPatchRepository.findOrCreateMainGitRepository(), true);
            Ref installationBranch = null;

            PatchKind kind = patchData.isRollupPatch() ? PatchKind.ROLLUP : PatchKind.NON_ROLLUP;

            switch (kind) {
                case ROLLUP: {
                    System.out.printf("Rolling back rollup patch \"%s\"%n", patchData.getId());
                    System.out.flush();

                    // rolling back a rollup patch should rebase all user commits on top of current baseline
                    // to previous basline
                    RevTag currentBaseline = gitPatchRepository.findCurrentBaseline(fork);
                    RevCommit c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(currentBaseline.getTagName() + "^{commit}"));
                    // remember the commit to discover P patch tags installed on top of rolledback baseline
                    RevCommit since = c1;
                    RevCommit c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
                    RevCommit to = c2;
                    Iterable<RevCommit> masterChangesSinceRollupPatch = fork.log().addRange(c1, c2).call();
                    List<RevCommit> userChanges = new LinkedList<>();
                    for (RevCommit rc : masterChangesSinceRollupPatch) {
                        if (isUserChangeCommit(rc)) {
                            userChanges.add(rc);
                        }
                    }

                    // remove the tag
                    fork.tagDelete()
                            .setTags(currentBaseline.getTagName())
                            .call();

                    // baselines are stacked on each other
                    RevTag previousBaseline = gitPatchRepository.findCurrentBaseline(fork);
                    c1 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve(previousBaseline.getTagName() + "^{commit}"));

                    // hard reset of master branch - to point to other branch, originating from previous baseline
                    fork.reset()
                            .setMode(ResetCommand.ResetType.HARD)
                            .setRef(previousBaseline.getTagName() + "^{commit}")
                            .call();

                    // reapply those user changes that are not conflicting
                    ListIterator<RevCommit> it = userChanges.listIterator(userChanges.size());

                    while (it.hasPrevious()) {
                        RevCommit userChange = it.previous();
                        CherryPickResult cpr = fork.cherryPick()
                                .include(userChange)
                                .setNoCommit(true)
                                .call();

                        // this time prefer user change on top of previous baseline - this change shouldn't be
                        // conflicting, because when rolling back, patch change was preferred over user change
                        handleCherryPickConflict(patchData.getPatchDirectory(), fork, cpr, userChange,
                                true, PatchKind.ROLLUP, null, false);

                        // restore backed up content from the reapplied user change
                        String[] commitMessage = userChange.getFullMessage().split("\n\n");
                        if (commitMessage.length > 1) {
                            // we have original commit that had conflicts stored in this commit's message
                            String ref = commitMessage[commitMessage.length - 1];
                            File backupDir = new File(patchesDir, patchData.getId() + ".backup");
                            backupDir = new File(backupDir, ref);
                            if (backupDir.exists() && backupDir.isDirectory()) {
                                System.out.printf("Restoring content of %s", backupDir.getCanonicalPath());
                                System.out.flush();
                                copyManagedDirectories(backupDir, karafHome, false);
                            }
                        }

                        gitPatchRepository.prepareCommit(fork, userChange.getFullMessage()).call();
                    }

                    gitPatchRepository.push(fork);
                    // remove remote tag
                    fork.push()
                            .setRefSpecs(new RefSpec()
                                    .setSource(null)
                                    .setDestination("refs/tags/" + currentBaseline.getTagName()))
                            .call();

                    // remove tags related to non-rollup patches installed between
                    // rolled back baseline and previous HEAD, because rolling back to previous rollup patch
                    // (previous baseline) equal effectively to starting from fresh baseline
                    RevWalk walk = new RevWalk(fork.getRepository());
                    Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, since, to);
                    for (Map.Entry<String, RevTag> entry : tags.entrySet()) {
                        if (entry.getKey().startsWith("patch-")) {
                            fork.tagDelete().setTags(entry.getKey()).call();
                            fork.push()
                                    .setRefSpecs(new RefSpec()
                                            .setSource(null)
                                            .setDestination("refs/tags/" + entry.getKey()))
                                    .call();
                        }
                    }

                    // HEAD of master branch after reset and cherry-picks
                    c2 = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
                    applyChanges(fork, c1, c2);

                    break;
                }
                case NON_ROLLUP: {
                    System.out.printf("Rolling back non-rollup patch \"%s\"%n", patchData.getId());
                    System.out.flush();

                    // rolling back a non-rollup patch is a revert of the patch commit and removal of patch tag

                    ObjectId oid = fork.getRepository().resolve(String.format("refs/tags/patch-%s^{commit}",
                            patchData.getId()));
                    if (oid == null) {
                        throw new PatchException(String.format("Can't find installed patch (tag patch-%s is missing)",
                                patchData.getId()));
                    }
                    RevCommit commit = new RevWalk(fork.getRepository()).parseCommit(oid);

                    RevertCommand revertCommand = fork.revert().include(commit);
                    RevCommit reverted = revertCommand.call();
                    if (reverted == null) {
                        List<String> unmerged = revertCommand.getUnmergedPaths();
                        System.out.println("Problem rolling back patch \"" + patchData.getId() + "\". The following files where updated later:");
                        for (String path : unmerged) {
                            System.out.println(" - " + path);
                        }
                        RevWalk walk = new RevWalk(fork.getRepository());
                        RevCommit head = walk.parseCommit(fork.getRepository().resolve("HEAD"));

                        Map<String, RevTag> tags = gitPatchRepository.findTagsBetween(fork, commit, head);
                        List<RevTag> laterPatches = new LinkedList<>();
                        if (tags.size() > 0) {
                            for (Map.Entry<String, RevTag> tag : tags.entrySet()) {
                                if (tag.getKey().startsWith("patch-")) {
                                    laterPatches.add(tag.getValue());
                                }
                            }
                            System.out.println("The following patches were installed after \"" + patchData.getId() + "\":");
                            for (RevTag t : laterPatches) {
                                System.out.print(" - " + t.getTagName().substring("patch-".length()));
                                RevObject object = walk.peel(t);
                                if (object != null) {
                                    RevCommit c = walk.parseCommit(object.getId());
                                    String date = GitPatchRepository.FULL_DATE.format(new Date(c.getCommitTime() * 1000L));
                                    System.out.print(" (" + date + ")");
                                }
                                System.out.println();
                            }
                        }
                        System.out.flush();
                        return;
                    }

                    // TODO: should we restore the backup possibly created when instalilng P patch?

                    // remove the tag
                    fork.tagDelete()
                            .setTags(String.format("patch-%s", patchData.getId()))
                            .call();

                    gitPatchRepository.push(fork);
                    // remove remote tag
                    fork.push()
                            .setRefSpecs(new RefSpec()
                                    .setSource(null)
                                    .setDestination(String.format("refs/tags/patch-%s", patchData.getId())))
                            .call();

                    // HEAD of master branch after reset and cherry-picks
                    RevCommit c = new RevWalk(fork.getRepository())
                            .parseCommit(fork.getRepository().resolve("HEAD"));
                    applyChanges(fork, c.getParent(0), c);

                    break;
                }
            }
        } catch (IOException | GitAPIException e) {
            throw new PatchException(e.getMessage(), e);
        } finally {
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * Resolve cherry-pick conflict before committing. Always prefer the change from patch, backup custom change
     * @param patchDirectory the source directory of the applied patch - used as a reference for backing up
     * conflicting files.
     * @param fork
     * @param result
     * @param commit conflicting commit
     * @param preferNew whether to use "theirs" change - the one from cherry-picked commit. for rollup patch, "theirs"
     * is user change, for non-rollup change, "theirs" is custom change
     * @param kind
     * @param cpPrefix prefix for a cherry-pick to have nice backup directory names.
     * @param performBackup if <code>true</code>, we backup rejected version (should be false during rollback of patches)
     */
    protected void handleCherryPickConflict(File patchDirectory, Git fork, CherryPickResult result, RevCommit commit,
                                            boolean preferNew, PatchKind kind, String cpPrefix, boolean performBackup)
            throws GitAPIException, IOException {
        if (result.getStatus() == CherryPickResult.CherryPickStatus.CONFLICTING) {
            System.out.println("Problem with applying the change " + commit.getName() + ":");
            Map<String, IndexDiff.StageState> conflicts = fork.status().call().getConflictingStageState();
            for (Map.Entry<String, IndexDiff.StageState> e : conflicts.entrySet()) {
                System.out.println(" - " + e.getKey() + ": " + e.getValue().name());
            }

            String choose = null, backup = null;
            switch (kind) {
                case ROLLUP:
                    choose = !preferNew ? "change from patch" : "custom change";
                    backup = !preferNew ? "custom change" : "change from patch";
                    break;
                case NON_ROLLUP:
                    choose = preferNew ? "change from patch" : "custom change";
                    backup = preferNew ? "custom change" : "change from patch";
                    break;
            }

            System.out.printf("Choosing %s%n", choose);
            DirCache cache = fork.getRepository().readDirCache();
            for (int i = 0; i < cache.getEntryCount(); i++) {
                DirCacheEntry entry = cache.getEntry(i);
                if (entry.getStage() == DirCacheEntry.STAGE_0 || entry.getStage() == DirCacheEntry.STAGE_1) {
                    continue;
                }
                if ((preferNew && entry.getStage() == DirCacheEntry.STAGE_3)
                        || (!preferNew && entry.getStage() == DirCacheEntry.STAGE_2)) {
                    ObjectLoader loader = fork.getRepository().newObjectReader().open(entry.getObjectId());
                    loader.copyTo(new FileOutputStream(new File(fork.getRepository().getWorkTree(), entry.getPathString())));
                    fork.add().addFilepattern(entry.getPathString()).call();
                } else if (performBackup) {
                    // the other entry should be backed up
                    ObjectLoader loader = fork.getRepository().newObjectReader().open(entry.getObjectId());
                    File target = new File(patchDirectory.getParent(), patchDirectory.getName() + ".backup");
                    if (cpPrefix != null) {
                        target = new File(target, cpPrefix);
                    }
                    File file = new File(target, entry.getPathString());
                    System.out.printf("Backing up %s to \"%s\"%n", backup, file.getCanonicalPath());
                    file.getParentFile().mkdirs();
                    loader.copyTo(new FileOutputStream(file));
                }
            }
            System.out.flush();
        }
    }

    /**
     * Very important method - {@link PatchKind#NON_ROLLUP non rollup patches} do not ship such files as
     * <code>etc/startup.properties</code>, but we <strong>have to</strong> update references to artifacts from those
     * files to point them to updated bundles.
     * Also we have to update/add <code>etc/overrides.properties</code> to have features working.
     * @param fork
     * @param patchData
     * @param bundleUpdatesInThisPatch
     */
    private void updateFileReferences(Git fork, PatchData patchData, List<BundleUpdate> bundleUpdatesInThisPatch) {
        if (patchData.isRollupPatch()) {
            return;
        }

        /*
         * we generally have a white list of files to update. We'll update them line by line if needed
         * these are the files & patterns to change:
         * bin/admin, bin/admin.bat, bin/client, bin/client.bat:
         *  - system/MAVEN_LOCATION (or \ in *.bat files)
         * etc/config.properties:
         *  - ${karaf.default.repository}/MAVEN_LOCATION
         *  - org.apache.karaf.jaas.boot;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.jaas.boot.principal;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.management.boot;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.version;version="2.4.0.redhat-620133", \
         *  - org.apache.karaf.diagnostic.core;version="2.4.0.redhat-620133", \
         *  these are the versions exported from lib/karaf.jar and this may be changed by non-rollup patch too...
         * etc/startup.properties:
         *  - MAVEN_LOCATION
         * etc/org.apache.karaf.features.cfg:
         *  - don't touch that file. NON-ROLLUP patches handle features using etc/overrides.properties and ROLLUP
         *  patches overwrite this file
         */

        Map<String, String> locationUpdates = Utils.collectLocationUpdates(bundleUpdatesInThisPatch);

        // update some files in generic way
        updateReferences(fork, "bin/admin", "system/", locationUpdates);
        updateReferences(fork, "bin/client", "system/", locationUpdates);
        updateReferences(fork, "etc/startup.properties", "", locationUpdates);
        updateReferences(fork, "bin/admin.bat", "system/", locationUpdates, true);
        updateReferences(fork, "bin/client.bat", "system/", locationUpdates, true);
        updateReferences(fork, "etc/config.properties", "${karaf.default.repository}/", locationUpdates);

        // update system karaf package versions in etc/config.properties
        File configProperties = new File(fork.getRepository().getWorkTree(), "etc/config.properties");
        for (String file : patchData.getFiles()) {
            if ("lib/karaf.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.version;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.version");
            } else if ("lib/karaf-jaas-boot.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.jaas.boot;version="2.4.0.redhat-620133", \
                //  - org.apache.karaf.jaas.boot.principal;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion,
                        "org.apache.karaf.jaas.boot",
                        "org.apache.karaf.jaas.boot.principal");
            } else if ("lib/karaf-jmx-boot.jar".equals(file)) {
                // update:
                //  - org.apache.karaf.management.boot;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.management.boot");
            } else if (file.startsWith("lib/org.apache.karaf.diagnostic.core-") && file.endsWith(".jar")) {
                // update:
                //  - org.apache.karaf.diagnostic.core;version="2.4.0.redhat-620133", \
                String newVersion = getBundleVersion(new File(fork.getRepository().getWorkTree(), file));
                updateKarafPackageVersion(configProperties, newVersion, "org.apache.karaf.diagnostic.core");
            }
        }

        // update etc/overrides.properties
    }

    /**
     * Changes prefixed references (to artifacts in <code>${karaf.default.repository}</code>) according to
     * a list of bundle updates.
     * @param fork
     * @param file
     * @param prefix
     * @param locationUpdates
     * @param useBackSlash
     */
    protected void updateReferences(Git fork, String file, String prefix, Map<String, String> locationUpdates, boolean useBackSlash) {
        File updatedFile = new File(fork.getRepository().getWorkTree(), file);
        if (!updatedFile.isFile()) {
            return;
        }

        BufferedReader reader = null;
        StringWriter sw = new StringWriter();
        try {
            System.out.println("[PATCH] Updating \"" + file + "\"");
            reader = new BufferedReader(new FileReader(updatedFile));
            String line = null;
            while ((line = reader.readLine()) != null) {
                for (Map.Entry<String, String> entry : locationUpdates.entrySet()) {
                    String pattern = prefix + entry.getKey();
                    String replacement = prefix + entry.getValue();
                    if (useBackSlash) {
                        pattern = pattern.replaceAll("/", "\\\\");
                        replacement = replacement.replaceAll("/", "\\\\");
                    }
                    if (line.contains(pattern)) {
                        line = line.replace(pattern, replacement);
                    }
                }
                sw.append(line);
                if (useBackSlash) {
                    // Assume it's .bat file
                    sw.append("\r");
                }
                sw.append("\n");
            }
            IOUtils.closeQuietly(reader);
            FileUtils.write(updatedFile, sw.toString());
        } catch (Exception e) {
            System.err.println("[PATCH-error] " + e.getMessage());
        } finally {
            IOUtils.closeQuietly(reader);
        }
    }

    /**
     * Changes prefixed references (to artifacts in <code>${karaf.default.repository}</code>) according to
     * a list of bundle updates.
     * @param fork
     * @param file
     * @param prefix
     * @param locationUpdates
     */
    private void updateReferences(Git fork, String file, String prefix, Map<String, String> locationUpdates) {
        updateReferences(fork, file, prefix, locationUpdates, false);
    }

    /**
     * Retrieves <code>Bundle-Version</code> header from JAR file
     * @param file
     * @return
     * @throws FileNotFoundException
     */
    private String getBundleVersion(File file) {
        JarInputStream jis = null;
        try {
            jis = new JarInputStream(new FileInputStream(file));
            Manifest mf = jis.getManifest();
            return mf.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
        } catch (Exception e) {
            return null;
        } finally {
            IOUtils.closeQuietly(jis);
        }
    }

    /**
     * Checks if the commit is user (non P-patch installation) change
     * @param rc
     * @return
     */
    protected boolean isUserChangeCommit(RevCommit rc) {
        return MARKER_USER_CHANGES_COMMIT.equals(rc.getShortMessage());
    }

    /**
     * Validates state of the transaction for install/commit/rollback purposes
     * @param transaction
     * @param patch
     */
    private void transactionIsValid(String transaction, Patch patch) {
        if (!pendingTransactions.containsKey(transaction)) {
            if (patch != null) {
                throw new PatchException(String.format("Can't proceed with \"%s\" patch - illegal transaction \"%s\".",
                        patch.getPatchData().getId(),
                        transaction));
            } else {
                throw new PatchException(String.format("Can't proceed - illegal transaction \"%s\".",
                        transaction));
            }
        }
    }

    @Override
    public boolean isEnabled() {
        return patchesDir != null && patchesDir.isDirectory() && patchesDir.exists();
    }

    @Override
    public void start() throws IOException {
        if (patchesDir != null) {
            gitPatchRepository.open();
        }
    }

    @Override
    public void stop() {
        if (patchesDir != null) {
            gitPatchRepository.close();
        }
    }

    /**
     * Check if Fuse/Fabric8 installation is correctly managed by patch mechanism. Check if main git repository
     * is created and is intialized with correct content, there are no conflicts and no pending updates in main Karaf
     * directory. After this method is invoked, we're basically ready to perform rollup patches backed up by git
     * repository.
     */
    @Override
    public void ensurePatchManagementInitialized() {
        System.out.println("[PATCH] INITIALIZING PATCH MANAGEMENT SYSTEM");
        System.out.flush();
        Git fork = null;
        try {
            Git mainRepository = gitPatchRepository.findOrCreateMainGitRepository();
            // prepare single fork for all the below operations
            fork = gitPatchRepository.cloneRepository(mainRepository, true);

            // 1) git history that tracks patch operations (but not the content of the patches)
            InitializationType state = checkMainRepositoryState(fork);
            switch (state) {
                case ERROR_NO_VERSIONS:
                    throw new PatchException("Can't determine Fuse/Fabric8 version. Is KARAF_HOME/fabric directory available?");
                case INSTALL_BASELINE:
                    // track initial configuration
                    trackBaselineRepository(fork);
                    // fall down the next case, don't break!
                case INSTALL_PATCH_MANAGEMENT_BUNDLE:
                    // install patch management bundle in etc/startup.properties to overwrite possible user change to that file
                    installPatchManagementBundle(fork);
                    // add possible user changes since the distro was first run
                    applyUserChanges(fork);
                    break;
                case ADD_USER_CHANGES:
                    // because patch management is already installed, we have to add consecutive (post patch-management installation) changes
                    applyUserChanges(fork);
                    break;
                case READY:
                    break;
            }

            // 2) repository of patch data - already installed patches
            migrateOldPatchData();
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
            e.printStackTrace(System.err);
            System.err.flush();
            // PANIC
        } finally {
            initialized.countDown();
            if (fork != null) {
                gitPatchRepository.closeRepository(fork, true);
            }
        }
    }

    /**
     * <p>Checks the state of git repository that track the patch history</p>
     * @param git a clone + working copy connected to main repository
     * @return the state the repository is at
     */
    protected InitializationType checkMainRepositoryState(Git git) throws GitAPIException, IOException {
        // we need baseline distribution of Fuse/AMQ at current version
        String currentFabricVersion = bundleContext.getBundle().getVersion().toString();
        String currentFuseVersion = determineVersion("fuse");
        if (currentFuseVersion == null) {
            return InitializationType.ERROR_NO_VERSIONS;
        }

        if (!gitPatchRepository.containsTag(git, String.format("baseline-%s", currentFuseVersion))) {
            // we have empty repository
            return InitializationType.INSTALL_BASELINE;
        } else if (!gitPatchRepository.containsCommit(git, "master", String.format(MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN, currentFabricVersion))) {
            // we already tracked the basline repo, but it seems we're running from patch-management bundle that was simply dropped into deploy/
            return InitializationType.INSTALL_PATCH_MANAGEMENT_BUNDLE;
        } else {
            System.out.println("[PATCH] Baseline distribution already committed for version " + currentFuseVersion);
            System.out.println("[PATCH] patch-management bundle is already installed in etc/startup.properties at version " + currentFabricVersion);
            System.out.flush();
            // we don't check if there are any user changes now, but we will be doing it anyway at each startup of this bundle
            return InitializationType.ADD_USER_CHANGES;
        }
    }

    /**
     * Return version of product (Fuse, Fabric8) used.
     * @param product
     * @return
     */
    private String determineVersion(String product) {
        return determineVersion(karafHome, product);
    }

    /**
     * Return version of product (Fuse, Fabric8) used, but probably based on different karafHome
     * @param home
     * @param product
     * @return
     */
    private String determineVersion(File home, String product) {
        File versions = new File(home, "fabric/import/fabric/profiles/default.profile/io.fabric8.version.properties");
        if (versions.exists() && versions.isFile()) {
            Properties props = new Properties();
            try {
                props.load(new FileInputStream(versions));
                return props.getProperty(product);
            } catch (IOException e) {
                System.err.println("[PATCH-error] " + e.getMessage());
                System.err.flush();
                return null;
            }
        } else {
            System.err.println("[PATCH-error] Can't find io.fabric8.version.properties file in default profile");
            System.err.flush();
        }
        return null;
    }

    /**
     * Adds baseline distribution to the repository
     * @param git non-bare repository to perform the operation
     */
    private void trackBaselineRepository(Git git) throws IOException, GitAPIException {
        // initialize repo with baseline version and push to reference repo
        String currentFuseVersion = determineVersion("fuse");
        String currentFabricVersion = determineVersion("fabric");

        // check what product are we in
        String baselineLocation = Utils.getBaselineLocationForProduct(karafHome, systemContext, currentFuseVersion);
        File systemRepo = getSystemRepository(karafHome, systemContext);
        File baselineDistribution = null;
        if (baselineLocation != null) {
            baselineDistribution = new File(patchesDir, baselineLocation);
        } else {
            // do some guessing - first JBoss Fuse, then JBoss A-MQ
            String[] locations = new String[] {
                    systemRepo.getCanonicalPath() + "/org/jboss/fuse/jboss-fuse-full/%1$s/jboss-fuse-full-%1$s-baseline.zip",
                    patchesDir.getCanonicalPath() + "/jboss-fuse-full-%1$s-baseline.zip",
                    systemRepo.getCanonicalPath() + "/org/jboss/amq/jboss-a-mq/%s/jboss-a-mq-%1$s-baseline.zip",
                    patchesDir.getCanonicalPath() + "/jboss-a-mq-%1$s-baseline.zip"
            };

            for (String location : locations) {
                location = String.format(location, currentFuseVersion);
                if (new File(location).isFile()) {
                    baselineDistribution = new File(location);
                    System.out.println("[PATCH] Found baseline distribution: " + baselineDistribution.getCanonicalPath());
                    break;
                }
            }
        }
        if (baselineDistribution != null) {
            unpack(baselineDistribution, git.getRepository().getWorkTree(), 1);

            git.add()
                    .addFilepattern(".")
                    .call();
            gitPatchRepository.prepareCommit(git, String.format(MARKER_BASELINE_COMMIT_PATTERN, currentFuseVersion)).call();

            // each baseline ships new feature repositories and from this point (or the point where new rollup patch
            // is installed) we should start with 0-sized overrides.properties in order to have easier non-rollup
            // patch installation - no P-patch should ADD overrides.properties - they have to only MODIFY it because
            // it's easier to revert such modification (in case P-patch is rolled back - probably in different order
            // than it was installed)
            resetOverrides(git.getRepository().getWorkTree());
            git.add().addFilepattern("etc/overrides.properties").call();
            RevCommit commit = gitPatchRepository.prepareCommit(git,
                    String.format(MARKER_BASELINE_RESET_OVERRIDES_PATTERN, currentFuseVersion)).call();
        } else {
            String message = "Can't find baseline distribution for version \"" + currentFuseVersion + "\" in patches dir or inside system repository.";
            System.err.println("[PATCH-error] " + message);
            System.err.flush();
            throw new PatchException(message);
        }
    }

    /**
     * <p>Applies existing user changes in ${karaf.home}/{bin,etc,fabric,lib,licenses,metatype} directories to patch
     * management Git repository, doesn't modify ${karaf.home}</p>
     * <p>TODO: Maybe we should ask user whether the change was intended or not? blacklist some changes?</p>
     * @param git non-bare repository to perform the operation
     */
    public void applyUserChanges(Git git) throws GitAPIException, IOException {
        File wcDir = git.getRepository().getDirectory().getParentFile();
        File karafBase = karafHome;

        try {
            // let's simply copy all user files on top of git working copy
            // then we can check the differences simply by committing the changes
            // there should be no conflicts, because we're not merging anything
            // "true" would mean that target dir is first deleted to detect removal of files
            copyManagedDirectories(karafBase, wcDir, false);

            // commit the changes to main repository
            Status status = git.status().call();
            if (!status.isClean()) {
                System.out.println("[PATCH] Storing user changes");
                git.add()
                        .addFilepattern(".")
                        .call();

                // let's not do removals when tracking user changes. if we do, cherry-picking user changes over
                // a rollup patch that introduced new file would simply
//                for (String name : status.getMissing()) {
//                    git.rm().addFilepattern(name).call();
//                }

                gitPatchRepository.prepareCommit(git, MARKER_USER_CHANGES_COMMIT).call();
                gitPatchRepository.push(git);

                // now main repository has exactly the same content as ${karaf.home}
                // We have two methods of synchronization wrt future rollup changes:
                // 1. we can pull from "origin" in the MAIN working copy (${karaf.hoome}) (making sure the copy is initialized)
                // 2. we can apply rollup patch to temporary fork + working copy, perform merges, resolve conflicts, etc
                //    and if everything goes fine, simply override ${karaf.hoome} content with the working copy content
                // method 2. doesn't require real working copy and ${karaf.home}/.git directory
            } else {
                System.out.println("[PATCH] No user changes");
            }
            System.out.flush();
        } catch (GitAPIException | IOException e) {
            System.err.println("[PATCH-error] " + e.getMessage());
            System.err.flush();
        }
    }

    /**
     * Copy content of managed directories from source (like ${karaf.home}) to target (e.g. working copy of git repository)
     * @param sourceDir
     * @param targetDir
     * @param removeTarget whether to delete content of targetDir/<em>managedDirectory</em> first (helpful to detect removals from source)
     * @throws IOException
     */
    private void copyManagedDirectories(File sourceDir, File targetDir, boolean removeTarget) throws IOException {
        for (String dir : MANAGED_DIRECTORIES) {
            File managedSrcDir = new File(sourceDir, dir);
            if (!managedSrcDir.exists()) {
                continue;
            }
            File destDir = new File(targetDir, dir);
            if (removeTarget) {
                FileUtils.deleteQuietly(destDir);
            }
            EOLFixingFileUtils.copyDirectory(managedSrcDir, targetDir, destDir);
            if ("bin".equals(dir)) {
                // repair file permissions
                for (File script : destDir.listFiles()) {
                    if (!script.getName().endsWith(".bat")) {
                        if (Files.getFileAttributeView(script.toPath(), PosixFileAttributeView.class) != null) {
                            Files.setPosixFilePermissions(script.toPath(), getPermissionsFromUnixMode(script, 0775));
                        }
                    }
                }
            }
        }
    }

    /**
     * Assuming there's no track of adding patch-management bundle to etc/startup.properties, we also have to
     * actually <em>install</em> this bundle in ${karaf.home}/system
     * @param git non-bare repository to perform the operation
     */
    private void installPatchManagementBundle(Git git) throws IOException, GitAPIException {
        // if user simply osgi:install the patch-management bundle then adding the mvn: URI to etc/startup.properties is enough
        // but it patch-management was dropped into deploy/, we have to copy it to ${karaf.default.repository}
        File fileinstallDeployDir = getDeployDir(karafHome);
        String bundleVersion = bundleContext.getBundle().getVersion().toString();
        String patchManagementArtifact = String.format("patch-management-%s.jar", bundleVersion);
        File deployedPatchManagement = new File(fileinstallDeployDir, patchManagementArtifact);
        if (deployedPatchManagement.exists() && deployedPatchManagement.isFile()) {
            // let's copy it to system/
            File systemRepo = getSystemRepository(karafHome, systemContext);
            String targetFile = String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar", bundleVersion, bundleVersion);
            File target = new File(systemRepo, targetFile);
            target.getParentFile().mkdirs();
            FileUtils.copyFile(deployedPatchManagement, target);
            // don't delete source artifact, because fileinstall can decide to uninstall the bundle!
            // we will do it in cleanupDeployDir()
        }

        bundleContext.getBundle().adapt(BundleStartLevel.class).setStartLevel(Activator.PATCH_MANAGEMENT_START_LEVEL);

        replacePatchManagementBundleInStartupPropertiesIfNecessary(git, bundleVersion);
    }

    /**
     * One stop method that does everything related to installing patch-management bundle in etc/startup.properties.
     * It removes old version of the bundle, doesn't do anything if the bundle is already there and appends a declaration if there was none.
     * @param git
     * @param bundleVersion
     * @throws IOException
     * @throws GitAPIException
     */
    private void replacePatchManagementBundleInStartupPropertiesIfNecessary(Git git, String bundleVersion) throws IOException, GitAPIException {
        boolean modified = false;
        boolean installed = false;

        File etcStartupProperties = new File(git.getRepository().getDirectory().getParent(), "etc/startup.properties");
        List<String> lines = FileUtils.readLines(etcStartupProperties);
        List<String> newVersion = new LinkedList<>();
        String lf = System.lineSeparator();
        for (String line : lines) {
            if (!line.startsWith("io/fabric8/patch/patch-management/")) {
                // copy unchanged
                newVersion.add(line);
            } else {
                // is it old, same, (newer??) version?
                Matcher matcher = VERSION_PATTERN.matcher(line);
                if (matcher.find()) {
                    // it should match
                    String alreadyInstalledVersion = matcher.group(1);
                    Version v1 = new Version(alreadyInstalledVersion);
                    Version v2 = new Version(bundleVersion);
                    if (v1.equals(v2)) {
                        // already installed at correct version
                        installed = true;
                    } else if (v1.compareTo(v2) < 0) {
                        // we'll install new version
                        modified = true;
                    } else {
                        // newer installed? why?
                    }
                }
            }
        }
        if (modified || !installed) {
            newVersion.add("");
            newVersion.add("# installed by patch-management");
            newVersion.add(String.format("io/fabric8/patch/patch-management/%s/patch-management-%s.jar=%d",
                    bundleVersion, bundleVersion, Activator.PATCH_MANAGEMENT_START_LEVEL));

            StringBuilder sb = new StringBuilder();
            for (String newLine : newVersion) {
                sb.append(newLine).append(lf);
            }
            FileUtils.write(new File(git.getRepository().getDirectory().getParent(), "etc/startup.properties"), sb.toString());

            // now to git working copy
            git.add()
                    .addFilepattern("etc/startup.properties")
                    .call();

            RevCommit commit = gitPatchRepository
                    .prepareCommit(git, String.format(MARKER_PATCH_MANAGEMENT_INSTALLATION_COMMIT_PATTERN, bundleVersion))
                    .call();

            // and we'll tag the baseline *after* clearing etc/overrides.properties
            String currentFuseVersion = determineVersion("fuse");
            git.tag()
                    .setName(String.format("baseline-%s", currentFuseVersion))
                    .setObjectId(commit)
                    .call();

            gitPatchRepository.push(git);

            // "checkout" the above change in main "working copy" (${karaf.home})
            applyChanges(git, commit.getParent(0), commit);

            System.out.println(String.format("[PATCH] patch-management-%s.jar installed in etc/startup.properties. Please restart.", bundleVersion));
            System.out.flush();
        }
    }

    /**
     * <p>This method takes a range of commits (<code>c1..c2</code>) and performs manual update to ${karaf.home}.
     * If ${karaf.home} was also a checked out working copy, it'd be a matter of <code>git pull</code>. We may consider
     * this implementation, but now I don't want to keep <code>.git</code> directory in ${karaf.home}. Also, jgit
     * doesn't support <code>.git</code> <em>platform agnostic symbolic link</em>
     * (see: <code>git init --separate-git-dir</code>)</p>
     * <p>We don't have to fetch data from repository blobs, because <code>git</code> still points to checked-out
     * working copy</p>
     * <p>TODO: maybe we just have to copy <strong>all</strong> files from working copy to ${karaf.home}?</p>
     * @param git
     * @param commit1
     * @param commit2
     */
    private void applyChanges(Git git, RevCommit commit1, RevCommit commit2) throws IOException, GitAPIException {
        File wcDir = git.getRepository().getWorkTree();

        List<DiffEntry> diff = this.gitPatchRepository.diff(git, commit1, commit2);

        // Changes to the lib dir get done in the lib.next directory.  Lets copy
        // the lib dir just in case we do have modification to it.
        FileUtils.copyDirectory(new File(karafHome, "lib"), new File(karafHome, "lib.next"));
        boolean libDirectoryChanged = false;

        for (DiffEntry de : diff) {
            DiffEntry.ChangeType ct = de.getChangeType();
            /*
             * old path:
             *  - file add: always /dev/null
             *  - file modify: always getNewPath()
             *  - file delete: always the file being deleted
             *  - file copy: source file the copy originates from
             *  - file rename: source file the rename originates from
             * new path:
             *  - file add: always the file being created
             *  - file modify: always getOldPath()
             *  - file delete: always /dev/null
             *  - file copy: destination file the copy ends up at
             *  - file rename: destination file the rename ends up at
             */
            String newPath = de.getNewPath();
            String oldPath = de.getOldPath();
            switch (ct) {
                case ADD:
                case MODIFY:
                    System.out.println("[PATCH-change] Modifying " + newPath);
                    String targetPath = newPath;
                    if (newPath.startsWith("lib/")) {
                        targetPath = "lib.next/" + newPath.substring(4);
                        libDirectoryChanged = true;
                    }
                    File srcFile = new File(wcDir, newPath);
                    File destFile = new File(karafHome, targetPath);
                    // we do exception for etc/overrides.properties
                    if ("etc/overrides.properties".equals(newPath)
                            && srcFile.exists() && srcFile.length() == 0) {
                        FileUtils.deleteQuietly(destFile);
                    } else {
                        FileUtils.copyFile(srcFile, destFile);
                    }
                    break;
                case DELETE:
                    System.out.println("[PATCH-change] Deleting " + oldPath);
                    if (oldPath.startsWith("lib/")) {
                        oldPath = "lib.next/" + oldPath.substring(4);
                        libDirectoryChanged = true;
                    }
                    FileUtils.deleteQuietly(new File(karafHome, oldPath));
                    break;
                case COPY:
                case RENAME:
                    // not handled now
                    break;
            }
        }

        if( !libDirectoryChanged ) {
            // lib.next directory might not be needed.
            FileUtils.deleteDirectory(new File(karafHome, "lib.next"));
        }
        System.out.flush();
    }

    @Override
    public void cleanupDeployDir() throws IOException {
        Version ourVersion = bundleContext.getBundle().getVersion();
        File deploy = getDeployDir(karafHome);
        File[] deployedPatchManagementBundles = deploy.listFiles(new FilenameFilter() {
            @Override
            public boolean accept(File dir, String name) {
                return VERSION_PATTERN.matcher(name).matches();
            }
        });
        for (File anotherPatchManagementBundle : deployedPatchManagementBundles) {
            Matcher matcher = VERSION_PATTERN.matcher(anotherPatchManagementBundle.getName());
            matcher.find();
            String version = matcher.group(1);
            Version deployedVersion = new Version(version);
            if (ourVersion.compareTo(deployedVersion) >= 0) {
                System.out.println("[PATCH] Deleting " + anotherPatchManagementBundle);
                FileUtils.deleteQuietly(anotherPatchManagementBundle);
            }
        }
        System.out.flush();
    }

    /**
     * Checks if current installation has old patch data available. If true, then move this data
     * under new patch management directory
     */
    private void migrateOldPatchData() throws IOException {
        // bundle data of Felix
        File systemBundleData = systemContext.getDataFile("patches");
        if (systemBundleData.exists() && systemBundleData.isDirectory()) {
            // move old data from ${karaf.home}/data/cache/bundle0/patches/
            movePatchData(systemBundleData);
        }
        String oldPatchLocation = systemContext.getProperty("fabric8.patch.location");
        if (oldPatchLocation != null && !"".equals(oldPatchLocation)) {
            // move old data from ${karaf.home}/patches/
            //movePatchData(new File(oldPatchLocation));
        }
        String newPatchLocation = systemContext.getProperty("fuse.patch.location");
        if (newPatchLocation != null && !"".equals(newPatchLocation)) {
            //movePatchData(new File(newPatchLocation));
        }
    }

    /**
     * Moves patch files, descriptors and results into managed location
     * @param systemBundleData
     */
    private void movePatchData(File systemBundleData) throws IOException {
        FileUtils.copyDirectory(systemBundleData, patchesDir);
        FileUtils.deleteDirectory(systemBundleData);
    }

    /**
     * State of <code>${fuse.patch.location}/.management/history</code> repository indicating next action required
     */
    public enum InitializationType {
        /** Everything is setup */
        READY,
        /** Baseline is committed into the repository and patch-management bundle is installed in etc/startup.properties */
        ADD_USER_CHANGES,
        /** Baseline is committed into the repository, patch-management bundle is still only in deploy/ directory and not in etc/startup.properties */
        INSTALL_PATCH_MANAGEMENT_BUNDLE,
        /** Baseline isn't installed yet (or isn't installed at desired version) */
        INSTALL_BASELINE,
        /** Can't determine version from the installation files */
        ERROR_NO_VERSIONS
    }

}
