package de.kai_morich.simple_bluetooth_terminal;

import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Spinner;

import androidx.appcompat.app.AppCompatActivity;
import androidx.navigation.ui.AppBarConfiguration;

import java.util.ArrayList;
import java.util.List;

import de.kai_morich.simple_bluetooth_terminal.databinding.ActivityChangeConfigBinding;

public class ChangeConfig extends AppCompatActivity {

    private AppBarConfiguration appBarConfiguration;
    private ActivityChangeConfigBinding binding;
    Spinner spinnerAirDataRate;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = ActivityChangeConfigBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        setSupportActionBar(binding.toolbar);

        //NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_change_config);
       // appBarConfiguration = new AppBarConfiguration.Builder(navController.getGraph()).build();
       // NavigationUI.setupActionBarWithNavController(this, navController, appBarConfiguration);

        spinnerAirDataRate = findViewById(R.id.spinnerAirDataRate);

        String airDate[]={"0.3K","1.2K","2.4K","4.8K","9.6K","19.2K","38.4K","62.5"};
        List<String> list = new ArrayList<String>();
        for (int i = 0; i < 8; i++) list.add(airDate[i]);
        ArrayAdapter<String> arrayAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, list);
        spinnerAirDataRate.setAdapter(arrayAdapter);
        spinnerAirDataRate.setSelection(MySingletonClass.getInstance().getairDataRate());


    }

//    @Override
//    public boolean onSupportNavigateUp() {
//        NavController navController = Navigation.findNavController(this, R.id.nav_host_fragment_content_change_config);
//        return NavigationUI.navigateUp(navController, appBarConfiguration)
//                || super.onSupportNavigateUp();
//    }

    public void setValues(View view) {
        MySingletonClass.getInstance().setAirDataRate( spinnerAirDataRate.getSelectedItemPosition());
        MySingletonClass.getInstance().setE22change(true);
        finish();
    }
}