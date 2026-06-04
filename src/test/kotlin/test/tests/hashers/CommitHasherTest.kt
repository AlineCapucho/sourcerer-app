// Copyright 2017 Sourcerer Inc. All Rights Reserved.
// Author: Liubov Yaronskaya (lyaronskaya@sourcerer.io)
// Author: Anatoly Kislov (anatoly@sourcerer.io)

package test.tests.hashers

import app.api.MockApi
import app.extractors.Extractor
import app.hashers.CommitHasher
import app.hashers.CommitCrawler
import app.model.*
import app.utils.RepoHelper
import org.eclipse.jgit.api.Git
import org.jetbrains.spek.api.Spek
import org.jetbrains.spek.api.dsl.given
import org.jetbrains.spek.api.dsl.it
import test.utils.TestRepo
import java.io.File
import java.util.stream.StreamSupport.stream
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitHasherTest : Spek({
    fun getRepoRehash(git: Git, localRepo: LocalRepo): String {

        val initialRevCommit = stream(git.log().call().spliterator(), false)
            .toList().first()
        return RepoHelper.calculateRepoRehash(Commit(initialRevCommit).rehash,
            localRepo)
    }

    fun getLastCommit(git: Git): Commit {
        val revCommits = stream(git.log().call().spliterator(), false).toList()
        val lastCommit = Commit(revCommits.first())
        return lastCommit
    }

    fun cleanRepos() {
        Runtime.getRuntime().exec("src/test/delete_repo.sh").waitFor()
    }

    val userName = "First Contributor"
    val userEmail = "test@domain.com"

    val secondUserName = "Second Contributor"
    val secondUserEmail = "test2@domain.com"

    // Creation of test repo.
    cleanRepos()
    val repoPath = "./tmp_repo/.git"
    val git = Git.init().setGitDir(File(repoPath)).call()
    val config = git.repository.config
    config.setString("user", null, "name", userName)
    config.setString("user", null, "email", userEmail)
    config.save()

    // Common parameters for CommitHasher.
    val gitHasher = Git.open(File(repoPath))
    val initialCommit = Commit(git.commit().setMessage("Initial commit").call())
    val repoRehash = RepoHelper.calculateRepoRehash(initialCommit.rehash,
        LocalRepo(repoPath).also { it.author = Author(userName, userEmail) })
    val repo = Repo(rehash = repoRehash,
                    initialCommitRehash = initialCommit.rehash)
    val emails = hashSetOf(userEmail, secondUserEmail)

    given("repo with initial commit and no history") {
        repo.commits = listOf()

        val errors = mutableListOf<Throwable>()
        val mockApi = MockApi(mockRepo = repo)
        val observable = CommitCrawler.getObservable(gitHasher, repo)
        CommitHasher(repo, mockApi, repo.commits.map {it.rehash}, emails)
            .updateFromObservable(observable, { e -> errors.add(e) })

        it ("has no errors") {
            assertEquals(0, errors.size)
        }

        it("send added commits") {
            assertEquals(1, mockApi.receivedAddedCommits.size)
        }

        it("doesn't send deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }
    }

    given("repo with initial commit") {
        repo.commits = listOf(getLastCommit(git))

        val errors = mutableListOf<Throwable>()
        val mockApi = MockApi(mockRepo = repo)
        val observable = CommitCrawler.getObservable(gitHasher, repo)
        CommitHasher(repo, mockApi, repo.commits.map {it.rehash}, emails)
            .updateFromObservable(observable, { e -> errors.add(e) })

        it ("has no errors") {
            assertEquals(0, errors.size)
        }

        it("doesn't send added commits") {
            assertEquals(0, mockApi.receivedAddedCommits.size)
        }

        it("doesn't send deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }
    }

    given("happy path: added one commit") {
        repo.commits = listOf(getLastCommit(git))

        val errors = mutableListOf<Throwable>()
        val mockApi = MockApi(mockRepo = repo)
        val revCommit = git.commit().setMessage("Second commit.").call()
        val addedCommit = Commit(revCommit)
        val observable = CommitCrawler.getObservable(gitHasher, repo)
        CommitHasher(repo, mockApi, repo.commits.map {it.rehash}, emails)
            .updateFromObservable(observable, { e -> errors.add(e) })

        it ("has no errors") {
            assertEquals(0, errors.size)
        }

        it("doesn't send deleted commits") {
            assertEquals(0, mockApi.receivedDeletedCommits.size)
        }

        it("posts one commit as added") {
            assertEquals(1, mockApi.receivedAddedCommits.size)
        }

        it("should be that the posted commit is added one") {
            assertEquals(addedCommit, mockApi.receivedAddedCommits.last())
        }
    }

    given("commits with language stats") {
        val lines = listOf("x = 1", "y = 2")

        val author = Author(userName, userEmail)

        val testRepoPath = "../testrepo-commit-hasher-lang-stats"
        val testRepo = TestRepo(testRepoPath)

        val mockApi = MockApi(mockRepo = repo)
        val observable = CommitCrawler.getObservable(testRepo.git, repo)

        it("sends language stats for python files") {
            for (i in 0..lines.size - 1) {
                val line = lines[i]
                val fileName = "file$i.py"
                testRepo.createFile(fileName, listOf(line))
                testRepo.commit(message = "$line in $fileName", author = author)
            }

            val errors = mutableListOf<Throwable>()
            val rehashes = (0..lines.size - 1).map { "r$it" }

            CommitHasher(repo, mockApi, rehashes, emails)
                    .updateFromObservable(observable, { e -> errors.add(e) })

            assertEquals(0, errors.size)

            val languageStats = mockApi.receivedAddedCommits
                .fold(mutableListOf<CommitStats>()) { allStats, commit ->
                    allStats.addAll(commit.stats)
                    allStats
                }.filter { it.type == Extractor.TYPE_LANGUAGE }

            val pythonStats = languageStats.filter { it.tech == "python" }
            assertEquals(2, pythonStats.size)
            assertEquals(2, pythonStats.map { it.numLinesAdded }.sum())
        }

        afterGroup {
            testRepo.destroy()
        }
    }

    given("commits with typescript files") {
        val lines = listOf("const x = 1;", "const y = 2;")

        val author = Author(userName, userEmail)

        val testRepoPath = "../testrepo-commit-hasher-typescript"
        val testRepo = TestRepo(testRepoPath)

        val mockApi = MockApi(mockRepo = repo)
        val observable = CommitCrawler.getObservable(testRepo.git, repo)

        it("sends language stats for typescript") {
            for (i in 0..lines.size - 1) {
                val line = lines[i]
                val fileName = "file$i.ts"
                testRepo.createFile(fileName, listOf(line))
                testRepo.commit(message = "$line in $fileName", author = author)
            }

            val errors = mutableListOf<Throwable>()
            val rehashes = (0..lines.size - 1).map { "r$it" }

            CommitHasher(repo, mockApi, rehashes, emails)
                    .updateFromObservable(observable, { e -> errors.add(e) })

            assertEquals(0, errors.size)

            val stats = mockApi.receivedAddedCommits
                    .fold(mutableListOf<CommitStats>()) { allStats, commit ->
                        allStats.addAll(commit.stats)
                        allStats
                    }
            val languageStats = stats.filter { it.type == Extractor.TYPE_LANGUAGE }
            assertEquals(2, languageStats.size)
            languageStats.forEach { stat ->
                assertEquals("typescript", stat.tech)
            }
        }

        afterGroup {
            testRepo.destroy()
        }
    }

    given("commit with multiple authors") {
        val lines = listOf("line 1", "line 2", "line 3", "line 4")

        val author1 = Author(userName, userEmail)
        val author2 = Author(secondUserName, secondUserEmail)

        val testRepoPath = "../testrepo-multiple-authors"
        val testRepo = TestRepo(testRepoPath)

        val mockApi = MockApi(mockRepo = repo)

        it("sends stats") {
            for (i in 0..lines.size - 1) {
                val line = lines[i]
                val fileName = "file$i.ext"
                testRepo.createFile(fileName, listOf(line))
                val message = "$line in $fileName\n\nCo-authored-by: ${author2
                        .name} <${author2.email}>"
                testRepo.commit(message = message, author = author1)
            }
            val gitHasherIn = Git.open(File(testRepoPath))
            val jgitObservable = CommitCrawler.getJGitObservable(gitHasherIn,
                extractCoauthors = true)
            val observable = CommitCrawler.getObservable(gitHasherIn,
                    jgitObservable, repo)

            val errors = mutableListOf<Throwable>()

            val rehashes = (0..lines.size - 1).map { "r$it" }

            CommitHasher(repo, mockApi, rehashes, emails)
                    .updateFromObservable(observable, { e -> errors.add(e) })

            assertEquals(0, errors.size)

            val stats = mockApi.receivedAddedCommits
            val actualAuthors = stats.map { it.author }.toHashSet()
            assertEquals(2, actualAuthors.size)
            assertTrue(author1 in actualAuthors)
            assertTrue(author2 in actualAuthors)
        }
    }

    cleanRepos()
})
