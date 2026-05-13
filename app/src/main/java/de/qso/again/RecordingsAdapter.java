package de.qso.again;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class RecordingsAdapter extends RecyclerView.Adapter<RecordingsAdapter.ViewHolder> {
    
    private final List<String> recordings;
    private final OnItemClickListener clickListener;
    private final OnItemClickListener deleteListener;
    
    public interface OnItemClickListener {
        void onClick(String path);
    }
    
    public RecordingsAdapter(List<String> recordings, OnItemClickListener clickListener, OnItemClickListener deleteListener) {
        this.recordings = recordings;
        this.clickListener = clickListener;
        this.deleteListener = deleteListener;
    }
    
    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_recording, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        String path = recordings.get(position);
        File file = new File(path);
        
        String timestamp = "";
        try {
            Pattern p = Pattern.compile("qso-again--(.+)\\.wav");
            Matcher m = p.matcher(file.getName());
            if (m.find()) {
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd--HH:mm:ss", Locale.US);
                Date date = sdf.parse(m.group(1));
                SimpleDateFormat displaySdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                timestamp = displaySdf.format(date);
            }
        } catch (Exception e) {
            timestamp = String.valueOf(file.lastModified());
        }
        
        if (timestamp.isEmpty()) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
            timestamp = sdf.format(new Date(file.lastModified()));
        }
        
        holder.tvTimestamp.setText(timestamp);
        holder.itemView.setOnClickListener(v -> clickListener.onClick(path));
        holder.btnDelete.setOnClickListener(v -> deleteListener.onClick(path));
    }
    
    @Override
    public int getItemCount() {
        return recordings.size();
    }
    
    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvTimestamp;
        ImageButton btnDelete;
        
        ViewHolder(View itemView) {
            super(itemView);
            tvTimestamp = itemView.findViewById(R.id.tvTimestamp);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}