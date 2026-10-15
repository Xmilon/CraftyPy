package net.xmilon.craftpy.client.mui;


import icyllis.modernui.annotation.NonNull;
import icyllis.modernui.annotation.Nullable;
import icyllis.modernui.fragment.Fragment;
import icyllis.modernui.util.DataSet;
import icyllis.modernui.view.View;
import icyllis.modernui.view.LayoutInflater;
import icyllis.modernui.view.ViewGroup;


/** The Docs page: the IDE guide, the Python API reference and the Java bridge (see {@link HelpPanel}). */
public class DocsFragment extends Fragment {

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable DataSet savedInstanceState) {
        return HelpPanel.create(requireContext(), 1);
    }
}
