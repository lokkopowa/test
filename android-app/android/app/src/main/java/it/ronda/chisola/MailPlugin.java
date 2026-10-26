package it.ronda.chisola;

import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import androidx.core.content.FileProvider;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Apre una nuova mail (Outlook se installato, altrimenti scelta dell'app) con
 * destinatari, oggetto, testo e allegati già compilati.
 * Gli allegati sono file già scritti nella cache dell'app (Filesystem, directory CACHE).
 */
@CapacitorPlugin(name = "Mail")
public class MailPlugin extends Plugin {

    private static final String OUTLOOK = "com.microsoft.office.outlook";

    @PluginMethod
    public void compose(PluginCall call) {
        try {
            String[] to = toArray(call.getArray("to", new JSArray()));
            String[] cc = toArray(call.getArray("cc", new JSArray()));
            String[] files = toArray(call.getArray("files", new JSArray()));

            ArrayList<Uri> uris = new ArrayList<>();
            String authority = getContext().getPackageName() + ".fileprovider";
            for (String name : files) {
                File f = new File(getContext().getCacheDir(), name);
                if (!f.exists()) {
                    call.reject("Allegato non trovato: " + name);
                    return;
                }
                uris.add(FileProvider.getUriForFile(getContext(), authority, f));
            }

            Intent intent = new Intent(uris.size() > 1 ? Intent.ACTION_SEND_MULTIPLE : Intent.ACTION_SEND);
            intent.setType(uris.isEmpty() ? "message/rfc822" : "image/jpeg");
            intent.putExtra(Intent.EXTRA_EMAIL, to);
            if (cc.length > 0) intent.putExtra(Intent.EXTRA_CC, cc);
            intent.putExtra(Intent.EXTRA_SUBJECT, call.getString("subject", ""));
            intent.putExtra(Intent.EXTRA_TEXT, call.getString("body", ""));
            if (uris.size() > 1) {
                intent.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
            } else if (uris.size() == 1) {
                intent.putExtra(Intent.EXTRA_STREAM, uris.get(0));
            }
            // il permesso di lettura va concesso su tutti gli allegati
            if (!uris.isEmpty()) {
                ClipData clip = ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                intent.setClipData(clip);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            boolean outlook = isInstalled(OUTLOOK);
            if (outlook) {
                intent.setPackage(OUTLOOK);
                getActivity().startActivity(intent);
            } else {
                getActivity().startActivity(Intent.createChooser(intent, "Invia schede con…"));
            }
            JSObject ret = new JSObject();
            ret.put("outlook", outlook);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject("Impossibile aprire la mail: " + e.getMessage());
        }
    }

    private boolean isInstalled(String pkg) {
        try {
            getContext().getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static String[] toArray(JSArray arr) throws Exception {
        List<String> list = arr.toList();
        return list.toArray(new String[0]);
    }
}
