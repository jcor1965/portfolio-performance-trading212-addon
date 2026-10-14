package org.trading212.ppaddon;

import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.MApplication;
import org.eclipse.e4.ui.model.application.commands.MCommandsFactory;
import org.eclipse.e4.ui.model.application.ui.menu.MMenu;
import org.eclipse.e4.ui.model.application.ui.menu.MMenuFactory;

/** Contributes only to the runtime application model, never to an original JAR/XMI. */
public final class MenuProcessor
{
    public static final String ID = "org.trading212.ppaddon.import";

    @Execute
    public void install(MApplication application)
    {
        // Avoid duplicate contributions when the processor runs again or when
        // Eclipse reloads a model that already contains this add-on's entries.
        for (var window : application.getChildren())
        {
            var target = find(window.getMainMenu());
            if (target == null || target.getChildren().stream().anyMatch(item -> ID.equals(item.getElementId())))
                continue;

            var command = application.getCommands().stream().filter(c -> ID.equals(c.getElementId())).findFirst()
                            .orElseGet(() -> {
                                var created = MCommandsFactory.INSTANCE.createCommand();
                                created.setElementId(ID);
                                created.setCommandName("Trading 212 CSV add-on");
                                application.getCommands().add(created);
                                return created;
                            });
            if (application.getHandlers().stream().noneMatch(h -> (ID + ".handler").equals(h.getElementId())))
            {
                var handler = MCommandsFactory.INSTANCE.createHandler();
                handler.setElementId(ID + ".handler");
                handler.setCommand(command);
                handler.setContributionURI("bundleclass://name.abuchen.portfolio.ui/org.trading212.ppaddon.ImportHandler");
                application.getHandlers().add(handler);
            }
            var item = MMenuFactory.INSTANCE.createHandledMenuItem();
            item.setElementId(ID);
            item.setLabel("Trading 212 (CSV - add-on)");
            item.setTooltip("Independent Trading 212 import add-on");
            item.setCommand(command);
            target.getChildren().add(item);
        }
    }

    private MMenu find(MMenu menu)
    {
        if (menu == null)
            return null;
        if ("name.abuchen.portfolio.ui.menu.file.import".equals(menu.getElementId()))
            return menu;
        for (var child : menu.getChildren())
        {
            if (child instanceof MMenu nested)
            {
                var found = find(nested);
                if (found != null)
                    return found;
            }
        }
        return null;
    }
}
