package com.skide.gui.project

import com.skide.CoreManager
import com.skide.core.management.ExternalHandler
import com.skide.core.management.OpenProject
import com.skide.gui.GUIManager
import com.skide.gui.Menus
import com.skide.gui.Prompts
import com.skide.gui.controllers.CreateProjectGUIController
import com.skide.gui.controllers.GeneralSettingsGUIController
import com.skide.gui.controllers.ProjectGUIController
import com.skide.gui.settings.SettingsGUIHandler
import com.skide.include.OpenFileHolder
import javafx.application.Platform
import javafx.scene.control.*
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.MouseButton
import javafx.scene.layout.BorderPane
import org.fxmisc.flowless.VirtualizedScrollPane
import org.fxmisc.richtext.CodeArea
import org.fxmisc.richtext.LineNumberFactory
import java.io.File
import java.util.*


class OpenProjectGuiManager(val openProject: OpenProject, val coreManager: CoreManager) {

    val openFiles = HashMap<File, OpenFileHolder>()
    val settings = SettingsGui(coreManager, this)
    val window = GUIManager.getWindow("ProjectGui.fxml", openProject.project.name, false)
    lateinit var lowerTabPaneEventManager: LowerTabPaneEventManager


    fun startGui(): ProjectGuiEventListeners {

        if (coreManager.configManager.get("theme") == "Dark") {
            window.scene.stylesheets.add("DarkHighlighting.css")
        } else {
            window.scene.stylesheets.add("HighlightingLight.css")
        }

        val controller = window.controller as ProjectGUIController
        val eventManager = ProjectGuiEventListeners(this, controller, coreManager)
        eventManager.guiReady = {
            window.stage.show()
        }
        window.closeListener = {
            closeHook()
        }
        eventManager.setup()
        lowerTabPaneEventManager = LowerTabPaneEventManager(controller, this, coreManager)
        lowerTabPaneEventManager.setup()

        return eventManager
    }

    fun closeHook() {
        openFiles.values.forEach {
            it.saveCode()
        }
        if (openProject.runConfs.size != 0) {

            Thread {
                val am = openProject.runConfs.size
                openProject.runConfs.forEach {
                    it.value.srv.kill()
                }
                Platform.runLater {

                    Prompts.infoCheck("Stopped Server", "Running servers had to be stopped", "SkIde stopped $am server", Alert.AlertType.INFORMATION)


                }
            }.start()
        }
        coreManager.projectManager.openProjects.remove(this.openProject)
    }

    val projectFiles = openProject.project.fileManager.projectFiles
}

class ProjectGuiEventListeners(private val openProjectGuiManager: OpenProjectGuiManager, private val controller: ProjectGUIController, val coreManager: CoreManager) {

    var guiReady = {}
    var contextMenuVisible: ContextMenu? = null

    val filesTab = {
        val tab = Tab("Project files")
        val treeView = TreeView<String>()
        //set the root item
        treeView.selectionModel.selectedItemProperty().addListener { _, _, newValue ->


            if (newValue == null) return@addListener
            val selectedItem = newValue as TreeItem<String>

            if (selectedItem != treeView.root) {

                if (openProjectGuiManager.projectFiles.containsKey(selectedItem.value)) {

                    Thread {
                        openFile(openProjectGuiManager.projectFiles[selectedItem.value]!!)
                    }.start()
                }
            }
        }

        tab.content = treeView
        Pair(tab, treeView)
    }.invoke()
    val structureTab = {
        val tab = Tab("Skript Structure")
        val treeView = TreeView<String>()
        tab.content = treeView
        tab.isDisable = true
        Pair(tab, treeView)
    }.invoke()


    fun setup() {


        replaceTemplateElements()
        registerBrowserEvents()
        registerEditorEvents()
        setupMainMenu()
        updateProjectFilesTreeView()
        guiReady()
    }

    fun openFile(f: File, isExternal: Boolean = false) {

        if (openProjectGuiManager.openFiles.containsKey(f)) {

            for ((file, holder) in openProjectGuiManager.openFiles) {

                if (file === f) {

                    holder.tabPane.selectionModel.select(holder.tab)
                    break

                }
            }

            return
        }
        val holder = OpenFileHolder(openProjectGuiManager.openProject, f, f.name, Tab(f.name), controller.editorMainTabPane, BorderPane(), CodeArea(), coreManager, isExternal = isExternal)

        openProjectGuiManager.openFiles.put(f, holder)
        setupNewTabForDisplay(holder)
    }

    fun updateProjectFilesTreeView() {

        val rootItem = TreeItem<String>(openProjectGuiManager.openProject.project.name)
        filesTab.second.root = rootItem

        for ((name, _) in openProjectGuiManager.projectFiles) {
            val item = TreeItem<String>(name)
            if (name.endsWith(".sk")) {
                val openIcon = Image(javaClass.getResource("/icon.png").toExternalForm())
                val openView = ImageView(openIcon)
                openView.fitWidth = 15.0
                openView.fitHeight = 15.0
                item.graphic = openView
            }
            rootItem.children.add(item)
        }

        rootItem.isExpanded = true

    }

    private fun setupNewTabForDisplay(holder: OpenFileHolder) {

        Platform.runLater {
            holder.area.style = "-fx-font-family: \"${coreManager.configManager.get("font")}\" !important; -fx-font-size: ${coreManager.configManager.get("font_size")}px;"
            holder.tab.isClosable = true
            holder.borderPane.center = VirtualizedScrollPane(holder.area)
            holder.borderPane.bottom = holder.currentStackBox
            holder.currentStackBox.prefHeight = 35.0
            holder.tab.content = holder.borderPane
            holder.tab.contextMenu = Menus.getMenuForRootPane(holder)
            holder.area.paragraphGraphicFactory = LineNumberFactory.get(holder.area)
            if (holder.name.endsWith(".sk")) holder.codeManager.setup(holder) else ExternalHandler(holder)
            registerEventsForNewFile(holder)
            holder.tabPane.tabs.add(holder.tab)
            holder.tabPane.selectionModel.select(holder.tab)
            if (holder.name.endsWith(".sk")) updateStructureTab(holder)
        }

    }


    fun registerEventsForNewFile(holder: OpenFileHolder) {

        holder.tab.setOnCloseRequest {
            holder.saveCode()
            openProjectGuiManager.openFiles.remove(holder.f)
            System.gc()
            if (openProjectGuiManager.openFiles.size == 0) {
                controller.browserTabPane.selectionModel.select(0)
                structureTab.first.isDisable = true
            }
        }
    }

    private fun replaceTemplateElements() {

        val templateTab = controller.templateTab
        controller.editorMainTabPane.tabs.remove(templateTab)
        setupBrowser()


    }

    private fun updateStructureTab(holder: OpenFileHolder) {

        if (structureTab.first.isDisabled) structureTab.first.isDisable = false

        Platform.runLater {
            structureTab.second.root = holder.codeManager.rootStructureItem
        }
    }


    private fun setupBrowser() {

        filesTab.first.onSelectionChangedProperty().addListener { _, _, _ ->

            //TODO
        }

        filesTab.second.setOnMouseReleased { ev ->
            if (contextMenuVisible == null) {
                if (ev.button == MouseButton.SECONDARY) {
                    if (filesTab.second.selectionModel.selectedItem == null) return@setOnMouseReleased
                    val selectedItem = filesTab.second.selectionModel.selectedItem as TreeItem<String>



                    contextMenuVisible = if (selectedItem == filesTab.second.root) Menus.getMenuForRootProject(openProjectGuiManager.openProject, filesTab.second, ev.screenX, ev.screenY)
                    else
                        Menus.getMenuForProjectFile(openProjectGuiManager.openProject, openProjectGuiManager.projectFiles[selectedItem.value]!!, filesTab.second, ev.screenX, ev.screenY)


                }
            } else {
                contextMenuVisible!!.hide()
                contextMenuVisible = null
            }

        }

        controller.browserTabPane.tabs.addAll(filesTab.first, structureTab.first)


    }

    private fun setupMainMenu() {

        val fileMenu = controller.mainBenuBar.menus[0]
        val closeItem = fileMenu.items.first()
        fileMenu.items.remove(closeItem)

        controller.mainBenuBar.menus[2].items[0].setOnAction {
            GUIManager.showAbout()
        }


        val skUnity = MenuItem("Log in to SkUnity")
        skUnity.setOnAction {
            if (coreManager.skUnity.login()) controller.mainBenuBar.menus[2].items.remove(skUnity)
        }
        controller.mainBenuBar.menus[2].items.add(skUnity)


        val otherProjects = Menu("Other projects")

        closeItem.setOnAction {
            openProjectGuiManager.window.close()
            openProjectGuiManager.closeHook()
        }

        val newProject = MenuItem("New Project")
        newProject.setOnAction {
            val window = GUIManager.getWindow("NewProjectGui.fxml", "Create new Project", false)
            window.controller as CreateProjectGUIController
            window.controller.initGui(coreManager, window)
            window.stage.isResizable = false


            window.stage.show()
        }
        val compileMenu = Menu("Compile")
        fileMenu.setOnShowing {
            otherProjects.items.clear()
            coreManager.configManager.projects.values.forEach {
                val open = coreManager.projectManager.openProjects.any { openProject -> it.id == openProject.project.id }
                if (!open) {
                    val item = MenuItem(it.name)
                    val pr = it
                    item.setOnAction {
                        coreManager.projectManager.openProject(pr)
                    }
                    otherProjects.items.add(item)
                }
            }

            compileMenu.items.clear()

            openProjectGuiManager.openProject.project.fileManager.compileOptions.forEach {
                val item = MenuItem(it.key)
                item.setOnAction { _ ->
                    openProjectGuiManager.openFiles.forEach { f ->
                        f.value.saveCode()
                    }
                    openProjectGuiManager.openProject.compiler.compile(openProjectGuiManager.openProject.project, it.value, openProjectGuiManager.lowerTabPaneEventManager.setupBuildLogTabForInput())
                }
                compileMenu.items.add(item)
            }
        }
        val projectSettings = MenuItem("Project Settings")
        projectSettings.setOnAction {
            openProjectGuiManager.settings.show()
        }
        val generalSettings = MenuItem("General Settings")
        generalSettings.setOnAction {

            val window = GUIManager.getWindow("GeneralSettingsGui.fxml", "Settings", false)

            SettingsGUIHandler(window.controller as GeneralSettingsGUIController, coreManager, window).init()

            window.stage.show()

        }
        val editServerConfMenu = Menu("Edit server Configuration")
        coreManager.serverManager.servers.forEach {
            val file = File(it.value.configuration.folder, "server.properties")
            if (file.exists()) {
                val tItem = MenuItem(it.value.configuration.name)
                tItem.setOnAction {
                    openProjectGuiManager.openProject.eventManager.openFile(file, true)
                }
                editServerConfMenu.items.add(tItem)
            }
        }
        fileMenu.items.addAll(newProject, projectSettings, otherProjects, compileMenu, generalSettings, editServerConfMenu, closeItem)
    }

    private fun registerBrowserEvents() {


    }

    private fun registerEditorEvents() {

        structureTab.second.selectionModel.selectedItemProperty().addListener { _, _, newValue ->

            if (newValue != null) {

                val item = newValue as TreeItem<String>

                val tab = controller.editorMainTabPane.selectionModel.selectedItem

                openProjectGuiManager.openFiles.values
                        .filter { it.tab == tab }
                        .forEach { it.codeManager.gotoItem(item) }

            }
        }
      /*
        controller.editorMainTabPane.setOnMousePressed {ev ->
            if(ev.isSecondaryButtonDown) {

                if(controller.editorMainTabPane.selectionModel.selectedItem == null) return@setOnMousePressed
                openProjectGuiManager.openFiles.values.forEach {

                    if(it.tab == controller.editorMainTabPane.selectionModel.selectedItem && !it.area.isFocused) {

                        Menus.getMenuForRootPane(it, controller.editorMainTabPane, ev.screenX, ev.screenY)
                    }
                }

            }
        }
       */
        controller.editorMainTabPane.selectionModel.selectedItemProperty().addListener { _, _, _ ->

            if (controller.editorMainTabPane.selectionModel.selectedItem != null) {

                val tab = controller.editorMainTabPane.selectionModel.selectedItem

                openProjectGuiManager.openFiles.values
                        .filter { it.tab == tab }
                        .forEach {
                            if (it.name.endsWith(".sk")) updateStructureTab(it)


                            if (!it.name.endsWith(".sk")) {
                                GUIManager.discord.update("Editing ${it.name}", "Coding")
                            } else {
                                GUIManager.discord.update("Editing script ${it.name}", "Coding")
                            }
                        }

            } else {
                controller.browserTabPane.selectionModel.select(0)
                structureTab.first.isDisable = true
            }

        }

    }


}