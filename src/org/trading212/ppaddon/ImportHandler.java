package org.trading212.ppaddon;

import name.abuchen.portfolio.ui.handlers.MenuHelper;

import java.io.File;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Named;

import org.eclipse.e4.core.di.annotations.CanExecute;
import org.eclipse.e4.core.di.annotations.Execute;
import org.eclipse.e4.ui.model.application.ui.basic.MPart;
import org.eclipse.e4.ui.services.IServiceConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Shell;

import name.abuchen.portfolio.datatransfer.Extractor;
import name.abuchen.portfolio.datatransfer.SecurityCache;
import name.abuchen.portfolio.model.Client;
import name.abuchen.portfolio.ui.Messages;
import name.abuchen.portfolio.ui.PortfolioPlugin;
import name.abuchen.portfolio.ui.UIConstants;
import name.abuchen.portfolio.ui.editor.FilePathHelper;
import name.abuchen.portfolio.ui.editor.PortfolioPart;
import name.abuchen.portfolio.ui.wizards.datatransfer.ImportExtractedItemsWizard;
import name.abuchen.portfolio.ui.wizards.datatransfer.ImportWizardDialog;

/** Uses the same import review, duplicate checks and destination selection as XTB. */
public class ImportHandler
{
    @CanExecute
    boolean isVisible(@Named(IServiceConstants.ACTIVE_PART) MPart part)
    {
        return MenuHelper.isClientPartActive(part);
    }

    @Execute
    public void execute(@Named(IServiceConstants.ACTIVE_PART) MPart part,
                    @Named(IServiceConstants.ACTIVE_SHELL) Shell shell)
    {
        MenuHelper.getActiveClient(part).ifPresent(client -> runImport(part, shell, client));
    }

    private void runImport(MPart part, Shell shell, Client client)
    {
        if (client.getAccounts().isEmpty())
        {
            MessageDialog.openError(shell, Messages.LabelError, Messages.MsgErrorAccountNotExist);
            return;
        }

        if (client.getPortfolios().isEmpty())
        {
            MessageDialog.openError(shell, Messages.LabelError, Messages.MsgErrorPortfolioNotExist);
            return;
        }

        try
        {
            var extractor = new Trading212CSVExtractor(client);
            var portfolioPart = (PortfolioPart) part.getObject();
            var helper = new FilePathHelper(portfolioPart, UIConstants.Preferences.CSV_IMPORT_PATH);

            var dialog = new FileDialog(shell, SWT.OPEN | SWT.MULTI);
            dialog.setText(extractor.getLabel());
            dialog.setFilterNames(MessageFormat.format("{0} ({1})", extractor.getLabel(), "*.csv")); //$NON-NLS-1$ //$NON-NLS-2$
            dialog.setFilterExtensions("*.csv;*.CSV"); //$NON-NLS-1$
            dialog.setFilterPath(helper.getPath());
            dialog.open();
            if (dialog.getFileNames().length == 0)
                return;

            helper.savePath(dialog.getFilterPath());
            List<Extractor.InputFile> files = new ArrayList<>();
            for (var name : dialog.getFileNames())
                files.add(new Extractor.InputFile(new File(dialog.getFilterPath(), name)));

            // Reuse securities across every selected file before opening the
            // official review wizard, which also checks existing transactions.
            var cache = new SecurityCache(client);
            Map<File, List<Exception>> exceptions = new HashMap<>();
            var items = new ArrayList<Extractor.Item>();
            for (var file : files)
            {
                var errors = new ArrayList<Exception>();
                items.addAll(extractor.extract(cache, file, errors));
                if (!errors.isEmpty())
                    exceptions.put(file.getFile(), errors);
            }

            Map<Extractor, List<Extractor.Item>> results = new HashMap<>();
            results.put(extractor, items);
            cache.addMissingSecurityItems(results);

            // Keep account selection, duplicate warnings and insertion in the
            // host wizard rather than writing directly into the user's wallet.
            var wizard = new ImportExtractedItemsWizard(client, portfolioPart.getPreferenceStore(), results,
                            exceptions, new HashMap<>(), portfolioPart);
            portfolioPart.inject(wizard);
            new ImportWizardDialog(Display.getDefault().getActiveShell(), wizard).open();
        }
        catch (IllegalArgumentException e)
        {
            PortfolioPlugin.log(e);
            MessageDialog.openError(shell, Messages.LabelError, e.getMessage());
        }
    }
}
